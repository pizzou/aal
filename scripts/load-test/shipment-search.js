import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const TOKEN = __ENV.ACCESS_TOKEN || '';
const RATE = Number(__ENV.RATE || 20);
const DURATION = __ENV.DURATION || '2m';

export const options = {
  scenarios: {
    shipment_search: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Math.max(RATE * 2, 20),
      maxVUs: Math.max(RATE * 5, 50),
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<1000', 'p(99)<2000'],
  },
};

export default function () {
  const response = http.get(`${BASE_URL}/api/shipments?page=0&size=20`, {
    headers: TOKEN ? { Authorization: `Bearer ${TOKEN}` } : {},
  });

  check(response, {
    'shipment search returns 200': (r) => r.status === 200,
  });
}
