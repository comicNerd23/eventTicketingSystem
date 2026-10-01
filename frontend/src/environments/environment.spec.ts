import { environment } from './environment';

describe('environment', () => {
  it('reaches the API same-origin under /api', () => {
    expect(environment.apiBaseUrl).toBe('/api');
  });

  it('derives the WebSocket URL from the page origin', () => {
    const scheme = location.protocol === 'https:' ? 'wss:' : 'ws:';
    expect(environment.wsBaseUrl).toBe(`${scheme}//${location.host}/api`);
  });
});
