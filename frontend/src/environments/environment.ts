// The API is always same-origin under /api (ADR-019): nginx proxies it in the container image,
// and proxy.conf.json does the same for `ng serve`. One build therefore works in every environment.
const wsScheme = location.protocol === 'https:' ? 'wss:' : 'ws:';

export const environment = {
  apiBaseUrl: '/api',
  wsBaseUrl: `${wsScheme}//${location.host}/api`
};
