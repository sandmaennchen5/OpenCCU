#!/usr/bin/env node
//
// Node.js based HTTP proxy to rewrite the Location: and non-relative
// urls used in the HomeMatic WebUI of HTTP requests coming from
// Home-Assistent UI so that the Ingress-based HA UI is able to embed
// the WebUI.
//
// Copyright (c) 2021-2026 Jens Maus <mail@jens-maus.de>
// Apache 2.0 License applies
//
// v1.0: initial version
// v1.1: adapted to http-proxy-middleware v3
// v1.2: implement session sid cookie storage
// v1.3: add optional per-Home-Assistant-user session storage
//

const express = require('express');
const { createProxyMiddleware, responseInterceptor } = require('http-proxy-middleware');
const ipaddr = require('ipaddr.js');
const crypto = require('crypto');
const fs = require('fs');
const http = require('http');
const https = require('https');
const path = require('path');

// increase default listener limit
require('events').EventEmitter.defaultMaxListeners = 40;

const REQUEST_TIMEOUT = 20 * 60 * 1000; // 20 min
const SID_COOKIE = 'openccu_ingress_sid';
const SESSION_DIRECTORY = process.env.HM_INGRESS_SESSION_DIR || '/data/ingress-sessions';
const UPSTREAM_URL = '{{ index . "webui-url" }}';

function addonOptions() {
  try {
    return JSON.parse(fs.readFileSync('/data/options.json', 'utf8'));
  } catch(error) {
    return {};
  }
}

const OPTIONS = addonOptions();
const REMEMBER_INGRESS_USERS = typeof(process.env.HM_REMEMBER_INGRESS_USERS) === 'string'
  ? /^(1|true|yes|on)$/i.test(process.env.HM_REMEMBER_INGRESS_USERS)
  : OPTIONS.remember_ingress_users === true;
const KEEPALIVE_INTERVAL = Number.isInteger(OPTIONS.ingress_keepalive_interval) &&
  OPTIONS.ingress_keepalive_interval >= 1 && OPTIONS.ingress_keepalive_interval <= 599
  ? OPTIONS.ingress_keepalive_interval
  : 250;
console.log(`Per-user Home Assistant ingress session storage ${REMEMBER_INGRESS_USERS ? 'enabled' : 'disabled'}.`);

function parseCookies(header) {
  return Object.fromEntries((header || '').split(';').flatMap(cookie => {
    const separator = cookie.indexOf('=');
    if(separator < 0) return [];
    try {
      return [[cookie.slice(0, separator).trim(), decodeURIComponent(cookie.slice(separator + 1).trim())]];
    } catch(error) {
      return [];
    }
  }));
}

function validSid(sid) {
  return typeof(sid) === 'string' && sid.length > 0 && sid.length <= 256 && /^[A-Za-z0-9@._-]+$/.test(sid);
}

function sidCookie(sid, ingressPath, clear = false) {
  const path = ingressPath && ingressPath.startsWith('/') ? ingressPath : '/';
  return `${SID_COOKIE}=${clear ? '' : encodeURIComponent(sid)}; Path=${path}; HttpOnly; SameSite=Lax${clear ? '; Max-Age=0' : ''}`;
}

function clearSidCookie(res, ingressPath) {
  res.append('Set-Cookie', sidCookie('', ingressPath, true));
}

function sessionFile(req) {
  if(!REMEMBER_INGRESS_USERS) return null;
  const userId = req.headers['x-remote-user-id'];
  if(typeof(userId) !== 'string' || userId.length === 0) return null;
  const digest = crypto.createHash('sha256').update(userId).digest('hex');
  return path.join(SESSION_DIRECTORY, `${digest}.json`);
}

function readUserSid(req) {
  const file = sessionFile(req);
  if(!file) return null;
  try {
    const sid = JSON.parse(fs.readFileSync(file, 'utf8')).sid;
    return validSid(sid) ? sid : null;
  } catch(error) {
    return null;
  }
}

function writeUserSid(req, sid) {
  const file = sessionFile(req);
  if(!file || !validSid(sid)) return;
  try {
    fs.mkdirSync(SESSION_DIRECTORY, { recursive: true, mode: 0o700 });
    const temporary = `${file}.${process.pid}.tmp`;
    fs.writeFileSync(temporary, JSON.stringify({ sid }), { mode: 0o600 });
    fs.renameSync(temporary, file);
  } catch(error) {
    console.error(`Unable to store per-user ingress session: ${error.message}`);
  }
}

function deleteUserSid(req) {
  const file = sessionFile(req);
  if(!file) return;
  try {
    fs.rmSync(file, { force: true });
  } catch(error) {
    console.error(`Unable to remove per-user ingress session: ${error.message}`);
  }
}

function rememberedSid(req) {
  if(REMEMBER_INGRESS_USERS) return readUserSid(req);
  return parseCookies(req.headers.cookie)[SID_COOKIE];
}

function keepStoredSessionsAlive() {
  let files;
  try {
    files = fs.readdirSync(SESSION_DIRECTORY).filter(file => file.endsWith('.json'));
  } catch(error) {
    if(error.code !== 'ENOENT') console.error(`Unable to read per-user ingress sessions: ${error.message}`);
    return;
  }

  for(const name of files) {
    const file = path.join(SESSION_DIRECTORY, name);
    let sid;
    try {
      sid = JSON.parse(fs.readFileSync(file, 'utf8')).sid;
    } catch(error) {}
    if(!validSid(sid)) {
      try { fs.rmSync(file, { force: true }); } catch(error) {}
      continue;
    }

    const target = new URL('esp/system.htm', UPSTREAM_URL.endsWith('/') ? UPSTREAM_URL : `${UPSTREAM_URL}/`);
    target.searchParams.set('sid', sid);
    target.searchParams.set('action', 'keepAlive');
    const client = target.protocol === 'https:' ? https : http;
    const request = client.get(target, { timeout: 10000 }, response => {
      response.resume();
      if([302, 401, 403, 500].includes(response.statusCode)) {
        try { fs.rmSync(file, { force: true }); } catch(error) {}
      }
    });
    request.on('timeout', () => request.destroy(new Error('timeout')));
    request.on('error', error => console.error(`OpenCCU ingress keep-alive failed: ${error.message}`));
  }
}

function isIngressIndexPath(path) {
  return path.endsWith('/index.htm');
}

function removeSidFromUrl(url) {
  const [path, queryString] = String(url || '').split('?', 2);
  if(typeof(queryString) === 'undefined') return path || '/';
  const params = new URLSearchParams(queryString);
  params.delete('sid');
  const query = params.toString();
  return query.length > 0 ? `${path}?${query}` : path;
}

const apiProxy = createProxyMiddleware({
  target: UPSTREAM_URL,
  pathFilter: '/',
  changeOrigin: true, // for vhosted sites
  //logger: console,
  selfHandleResponse: true,
  // Use upstream proxyTimeout here; incoming client-facing timeouts are set once on the server below.
  proxyTimeout: REQUEST_TIMEOUT,
  on: {
    proxyRes: responseInterceptor(async (responseBody, proxyRes, req, res) => {
      const ingressPath = req.headers['x-ingress-path'] || '/';
      const savedSid = rememberedSid(req);
      const querySid = req.query.sid;

      if(proxyRes.statusCode === 500 &&
         isIngressIndexPath(req.path) &&
         validSid(querySid) &&
         validSid(savedSid) &&
         querySid === savedSid) {
        if(REMEMBER_INGRESS_USERS) deleteUserSid(req);
        else clearSidCookie(res, ingressPath);
        res.statusCode = 302;
        res.setHeader('location', `${ingressPath}${removeSidFromUrl(req.originalUrl)}`);
        return '';
      }

      // modify Location: response header if present
      if(typeof(proxyRes.headers.location) !== 'undefined') {
        // replace any absolute http/https path with a relative one
        var redirect = proxyRes.headers.location.replace(/(http|https):\/\/(.*?)\//, '/');
        redirect = req.headers['x-ingress-path'] + redirect;
        res.setHeader('location', redirect);
      }

      // modifying textual response bodies
      if(proxyRes.headers['content-type'] &&
         (
          proxyRes.headers['content-type'].includes('text/') ||
          proxyRes.headers['content-type'].includes('application/javascript') ||
          proxyRes.headers['content-type'].includes('application/json')
         )
        ) {

        var body;

        // if this a textual response body we make sure to prepend the ingress path
        if(proxyRes.headers['content-type'].toLowerCase().includes('utf-8') || proxyRes.req.path.includes('/jpages/')) {
          body = responseBody.toString('utf8');
        } else {
          body = responseBody.toString('latin1');
        }

        body = body.replace(/(?<=["'= \(\\]|\\u0027)\/(api|webui|ise|pda|config|pages|jpages|esp|upnp|tools|addons|tailscale)(\\?\/)(?!hassio_ingress)/g,
                            req.headers['x-ingress-path']+'/$1$2');
        body = body.replace(/(?<=["'])\/(index|login|logout)\.htm/g,
                            req.headers['x-ingress-path']+'/$1.htm');
        body = body.replace(/window\.location\.href='\/'/g,
                            'window.location.href=\'' + req.headers['x-ingress-path'] + '/\'');
        body = body.replace(/window\.location\.href='\/index\.htm'/g,
                            'window.location.href=\'' + req.headers['x-ingress-path'] + '/index.htm\'');

        // convert back to a Buffer in the right character encoding
        if(typeof(req.headers['content-type']) === 'undefined' && req.path.includes('/jpages/') === false) {
          return new Buffer.from(body, 'latin1');
        } else {
          return new Buffer.from(body, 'utf8');
        }
      } else {
        return responseBody;
      }
    }),
  },
});

const app = express();
app.use((req, res, next) => {
  //Get whitelisted range
  let whitelisted_range = ipaddr.parseCIDR(process.env.HM_HAPROXY_SRC);
  //Get source IP
  let source_ip = ipaddr.parse(req.ip.split(':').pop());
  //Check if source IP in whitelisted range
  if(source_ip.match(whitelisted_range)) {
    // allowed, forward to next middleware (proxy)
    next();
  } else {
    // abort request with "403 Forbidden"
    res.status(403).end();
  }
}, (req, res, next) => {
  const ingressPath = req.headers['x-ingress-path'] || '/';
  const isLogout = req.path === '/logout.htm';

  if(isLogout) {
    if(REMEMBER_INGRESS_USERS) deleteUserSid(req);
    else clearSidCookie(res, ingressPath);
    return next();
  }

  const savedSid = rememberedSid(req);
  if(validSid(req.query.sid)) {
    if(REMEMBER_INGRESS_USERS) {
      if(savedSid !== req.query.sid) writeUserSid(req, req.query.sid);
    } else if(!validSid(savedSid) || savedSid === req.query.sid) {
      res.append('Set-Cookie', sidCookie(req.query.sid, ingressPath));
    }
    return next();
  }

  if(isIngressIndexPath(req.path) && validSid(savedSid)) {
    const originalUrlWithoutSid = removeSidFromUrl(req.originalUrl);
    const querySeparator = originalUrlWithoutSid.includes('?') ? '&' : '?';
    return res.redirect(302, `${ingressPath}${originalUrlWithoutSid}${querySeparator}sid=${encodeURIComponent(savedSid)}`);
  }

  next();
}, apiProxy);

// listen on port 8099
const server = app.listen(8099, (err) => {
  if(err) {
    console.error(`ERROR: could not start ha-proxy: ${err}`);
  } else {
    console.log('Serving proxy requests for ' + '{{ index . "webui-url" }}' + ' on port 8099.');
  }
});

server.setTimeout(REQUEST_TIMEOUT);
server.requestTimeout = REQUEST_TIMEOUT;

if(REMEMBER_INGRESS_USERS) {
  console.log(`Keeping per-user ingress sessions alive every ${KEEPALIVE_INTERVAL} seconds.`);
  setInterval(keepStoredSessionsAlive, KEEPALIVE_INTERVAL * 1000).unref();
}
