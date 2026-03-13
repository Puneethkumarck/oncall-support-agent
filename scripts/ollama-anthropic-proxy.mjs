#!/usr/bin/env node
/**
 * Ollama-to-Anthropic proxy.
 * Accepts Ollama API format on port 11434 and forwards to Anthropic Claude API.
 * This lets the Embabel Ollama starter use Anthropic models transparently.
 */
import http from 'node:http';
import https from 'node:https';

const ANTHROPIC_API_KEY = process.env.ANTHROPIC_API_KEY;
const PORT = parseInt(process.env.PROXY_PORT || '11434');
const ANTHROPIC_MODEL = process.env.ANTHROPIC_MODEL || 'claude-haiku-4-5';

if (!ANTHROPIC_API_KEY) {
  console.error('ANTHROPIC_API_KEY environment variable required');
  process.exit(1);
}

function callAnthropic(messages, model, maxTokens) {
  return new Promise((resolve, reject) => {
    // Use streaming to avoid idle connection timeouts
    const payload = JSON.stringify({
      model: model,
      max_tokens: maxTokens || 4096,
      messages: messages,
      stream: true,
    });

    const req = https.request({
      hostname: 'api.anthropic.com',
      path: '/v1/messages',
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'x-api-key': ANTHROPIC_API_KEY,
        'anthropic-version': '2023-06-01',
        'Content-Length': Buffer.byteLength(payload),
      },
    }, (res) => {
      let rawData = '';
      let textContent = '';
      let usage = {};

      res.on('data', chunk => {
        rawData += chunk.toString();
        // Parse SSE events as they arrive
        const lines = rawData.split('\n');
        rawData = lines.pop() || ''; // keep incomplete line
        for (const line of lines) {
          if (!line.startsWith('data: ')) continue;
          const data = line.slice(6).trim();
          if (data === '[DONE]') continue;
          try {
            const event = JSON.parse(data);
            if (event.type === 'content_block_delta' && event.delta?.text) {
              textContent += event.delta.text;
            }
            if (event.type === 'message_delta' && event.usage) {
              usage = { ...usage, output_tokens: event.usage.output_tokens };
            }
            if (event.type === 'message_start' && event.message?.usage) {
              usage = { ...usage, input_tokens: event.message.usage.input_tokens };
            }
          } catch (e) { /* skip unparseable lines */ }
        }
      });

      res.on('end', () => {
        // Return in the same format as non-streaming API
        resolve({
          content: [{ type: 'text', text: textContent }],
          usage: usage,
        });
      });
    });

    req.on('error', reject);
    req.write(payload);
    req.end();
  });
}

function convertOllamaToAnthropicMessages(ollamaMessages) {
  if (!ollamaMessages || ollamaMessages.length === 0) return [{ role: 'user', content: 'Hello' }];

  const messages = [];
  let systemPrompt = null;

  for (const msg of ollamaMessages) {
    if (msg.role === 'system') {
      systemPrompt = msg.content;
    } else {
      messages.push({ role: msg.role, content: msg.content });
    }
  }

  // Anthropic requires messages to start with 'user'
  if (messages.length === 0) {
    messages.push({ role: 'user', content: systemPrompt || 'Hello' });
    systemPrompt = null;
  }

  return { messages, system: systemPrompt };
}

function stripCodeFences(text) {
  // Remove ```json ... ``` wrapping
  let cleaned = text.trim();
  if (cleaned.startsWith('```')) {
    cleaned = cleaned.replace(/^```(?:json)?\s*\n?/, '').replace(/\n?```\s*$/, '');
  }
  return cleaned.trim();
}

function convertToOllamaResponse(anthropicResp, model) {
  const content = stripCodeFences(anthropicResp.content?.[0]?.text || '');
  return {
    model: model,
    created_at: new Date().toISOString(),
    message: { role: 'assistant', content: content },
    done: true,
    done_reason: 'stop',
    total_duration: 0,
    load_duration: 0,
    prompt_eval_count: anthropicResp.usage?.input_tokens || 0,
    prompt_eval_duration: 0,
    eval_count: anthropicResp.usage?.output_tokens || 0,
    eval_duration: 0,
  };
}

const server = http.createServer(async (req, res) => {
  // Handle Ollama tags/models endpoint
  if (req.url === '/api/tags' || req.url === '/api/tags/') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
      models: [{
        name: 'claude-haiku-4-5:latest',
        model: 'claude-haiku-4-5:latest',
        modified_at: new Date().toISOString(),
        size: 0,
        digest: 'anthropic-proxy',
        details: { family: 'anthropic', parameter_size: 'cloud', quantization_level: 'none' },
      }]
    }));
    return;
  }

  // Handle version endpoint
  if (req.url === '/api/version' || req.url === '/') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ version: '0.0.1-anthropic-proxy' }));
    return;
  }

  // Handle chat endpoint
  if (req.url === '/api/chat' && req.method === 'POST') {
    let body = '';
    req.on('data', chunk => body += chunk);
    req.on('end', async () => {
      try {
        const ollamaReq = JSON.parse(body);
        const model = ANTHROPIC_MODEL;
        const { messages, system } = convertOllamaToAnthropicMessages(ollamaReq.messages);

        // Always force JSON output - Embabel always needs structured JSON responses
        if (messages.length > 0) {
          const lastMsg = messages[messages.length - 1];
          lastMsg.content = lastMsg.content + '\n\nIMPORTANT: You MUST respond with ONLY valid JSON. No markdown, no explanations, no code fences, no backticks. Output ONLY the raw JSON object starting with { and ending with }. All field names must be camelCase. Use null for unknown values. Lists must be JSON arrays [].';
        }

        const anthropicPayload = { model, max_tokens: 4096, messages };
        const jsonSystemMsg = 'CRITICAL: Your entire response must be a single valid JSON object. Start with { end with }. No markdown, no code blocks, no backticks, no text before or after. Only raw JSON.';
        if (system) {
          anthropicPayload.system = system + '\n\n' + jsonSystemMsg;
        } else {
          anthropicPayload.system = jsonSystemMsg;
        }

        const startTime = Date.now();
        const wantsStream = ollamaReq.stream !== false; // Ollama defaults to streaming
        console.log(`[proxy] /api/chat → Anthropic ${model} (${messages.length} msgs, json=FORCED, stream=${wantsStream})`);

        if (wantsStream) {
          // Stream mode: send Ollama-format JSON lines as Anthropic streams tokens
          res.writeHead(200, { 'Content-Type': 'application/x-ndjson' });

          const anthropicPayloadStr = JSON.stringify(anthropicPayload);
          const streamReq = https.request({
            hostname: 'api.anthropic.com',
            path: '/v1/messages',
            method: 'POST',
            headers: {
              'Content-Type': 'application/json',
              'x-api-key': ANTHROPIC_API_KEY,
              'anthropic-version': '2023-06-01',
              'Content-Length': Buffer.byteLength(anthropicPayloadStr),
            },
          }, (streamRes) => {
            let rawData = '';
            let fullText = '';

            streamRes.on('data', chunk => {
              rawData += chunk.toString();
              const lines = rawData.split('\n');
              rawData = lines.pop() || '';
              for (const line of lines) {
                if (!line.startsWith('data: ')) continue;
                const data = line.slice(6).trim();
                if (data === '[DONE]') continue;
                try {
                  const event = JSON.parse(data);
                  if (event.type === 'content_block_delta' && event.delta?.text) {
                    fullText += event.delta.text;
                    // Send partial Ollama response to keep connection alive
                    res.write(JSON.stringify({
                      model: ollamaReq.model || model,
                      created_at: new Date().toISOString(),
                      message: { role: 'assistant', content: event.delta.text },
                      done: false,
                    }) + '\n');
                  }
                } catch (e) { /* skip */ }
              }
            });

            streamRes.on('end', () => {
              const elapsed = ((Date.now() - startTime) / 1000).toFixed(1);
              console.log(`[proxy] Response (${elapsed}s): ${fullText.substring(0, 80)}...`);
              // Send final Ollama response
              res.write(JSON.stringify({
                model: ollamaReq.model || model,
                created_at: new Date().toISOString(),
                message: { role: 'assistant', content: '' },
                done: true,
                done_reason: 'stop',
                total_duration: 0,
                eval_count: 0,
              }) + '\n');
              res.end();
            });

            streamRes.on('error', (err) => {
              console.error(`[proxy] Stream error: ${err.message}`);
              res.end(JSON.stringify({ error: err.message }));
            });
          });

          // Add stream:true to the Anthropic payload
          const payloadWithStream = { ...anthropicPayload, stream: true };
          streamReq.on('error', (err) => {
            console.error(`[proxy] Request error: ${err.message}`);
            res.writeHead(500, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ error: err.message }));
          });
          streamReq.write(JSON.stringify(payloadWithStream));
          streamReq.end();
        } else {
          // Non-stream mode but use Anthropic streaming internally.
          // Build the Ollama JSON response progressively using chunked encoding.
          // This keeps Netty's connection alive because bytes are flowing.
          res.writeHead(200, { 'Content-Type': 'application/json' });

          const payloadWithStream = { ...anthropicPayload, stream: true };
          const anthropicPayloadStr = JSON.stringify(payloadWithStream);

          const streamReq = https.request({
            hostname: 'api.anthropic.com',
            path: '/v1/messages',
            method: 'POST',
            headers: {
              'Content-Type': 'application/json',
              'x-api-key': ANTHROPIC_API_KEY,
              'anthropic-version': '2023-06-01',
              'Content-Length': Buffer.byteLength(anthropicPayloadStr),
            },
          }, (streamRes) => {
            let rawData = '';
            let fullText = '';
            let usage = {};
            let headerSent = false;

            streamRes.on('data', chunk => {
              rawData += chunk.toString();
              const lines = rawData.split('\n');
              rawData = lines.pop() || '';
              for (const line of lines) {
                if (!line.startsWith('data: ')) continue;
                const data = line.slice(6).trim();
                if (data === '[DONE]') continue;
                try {
                  const event = JSON.parse(data);
                  if (event.type === 'content_block_delta' && event.delta?.text) {
                    fullText += event.delta.text;
                    // On first token, send the JSON prefix to keep connection alive
                    if (!headerSent) {
                      const prefix = `{"model":"${ollamaReq.model || model}","created_at":"${new Date().toISOString()}","message":{"role":"assistant","content":"`;
                      res.write(prefix);
                      headerSent = true;
                    }
                    // Stream each token as part of the content string (escaped)
                    const escaped = event.delta.text.replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\n/g, '\\n').replace(/\r/g, '\\r').replace(/\t/g, '\\t');
                    res.write(escaped);
                  }
                  if (event.type === 'message_start' && event.message?.usage) {
                    usage = { ...usage, input_tokens: event.message.usage.input_tokens };
                  }
                  if (event.type === 'message_delta' && event.usage) {
                    usage = { ...usage, output_tokens: event.usage.output_tokens };
                  }
                } catch (e) { /* skip */ }
              }
            });

            streamRes.on('end', () => {
              const elapsed = ((Date.now() - startTime) / 1000).toFixed(1);
              fullText = stripCodeFences(fullText);
              console.log(`[proxy] Response (${elapsed}s): ${fullText.substring(0, 80)}...`);

              if (!headerSent) {
                // No tokens received — send empty response
                res.end(JSON.stringify({
                  model: ollamaReq.model || model,
                  created_at: new Date().toISOString(),
                  message: { role: 'assistant', content: '' },
                  done: true, done_reason: 'stop',
                }));
              } else {
                // Close the content string and JSON object
                res.end(`"},"done":true,"done_reason":"stop","total_duration":0,"prompt_eval_count":${usage.input_tokens || 0},"eval_count":${usage.output_tokens || 0}}`);
              }
            });

            streamRes.on('error', (err) => {
              console.error(`[proxy] Stream error: ${err.message}`);
              if (!headerSent) {
                res.end(JSON.stringify({ error: err.message }));
              } else {
                res.end(`"},"done":true,"error":"${err.message}"}`);
              }
            });
          });

          streamReq.on('error', (err) => {
            console.error(`[proxy] Request error: ${err.message}`);
            res.end(JSON.stringify({ error: err.message }));
          });
          streamReq.write(anthropicPayloadStr);
          streamReq.end();
        }
      } catch (err) {
        console.error(`[proxy] Error: ${err.message}`);
        res.writeHead(500, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: err.message }));
      }
    });
    return;
  }

  // Handle generate endpoint (simpler format)
  if (req.url === '/api/generate' && req.method === 'POST') {
    let body = '';
    req.on('data', chunk => body += chunk);
    req.on('end', async () => {
      try {
        const ollamaReq = JSON.parse(body);
        const model = ANTHROPIC_MODEL;
        const messages = [{ role: 'user', content: ollamaReq.prompt || '' }];

        console.log(`[proxy] /api/generate → Anthropic ${model}`);
        const anthropicResp = await callAnthropic(messages, model, ollamaReq.options?.num_predict || 4096);

        if (anthropicResp.error) {
          res.writeHead(500, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ error: anthropicResp.error.message }));
          return;
        }

        const content = anthropicResp.content?.[0]?.text || '';
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
          model: ollamaReq.model || model,
          created_at: new Date().toISOString(),
          response: content,
          done: true,
          done_reason: 'stop',
        }));
      } catch (err) {
        console.error(`[proxy] Error: ${err.message}`);
        res.writeHead(500, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: err.message }));
      }
    });
    return;
  }

  // Fallback
  res.writeHead(404, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify({ error: `Unknown endpoint: ${req.method} ${req.url}` }));
});

// Set server timeouts to 5 minutes to handle long LLM responses (PostMortem etc.)
server.timeout = 300000;
server.keepAliveTimeout = 300000;
server.headersTimeout = 310000;
server.requestTimeout = 300000;

server.listen(PORT, () => {
  console.log(`[proxy] Ollama-to-Anthropic proxy running on port ${PORT}`);
  console.log(`[proxy] Model: ${ANTHROPIC_MODEL}`);
  console.log(`[proxy] Server timeout: 300s`);
  console.log(`[proxy] Forwarding Ollama API calls → Anthropic API`);
});
