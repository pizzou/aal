import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const RATE = Number(__ENV.RATE || 10);
const DURATION = __ENV.DURATION || '2m';

export const options = {
  scenarios: {
    public_quote: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Math.max(RATE * 2, 20),
      maxVUs: Math.max(RATE * 5, 50),
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.02'],
    http_req_duration: ['p(95)<1500', 'p(99)<3000'],
  },
};

export default function () {
  const response = http.post(
    `${BASE_URL}/api/public/commercial/quotes`,
    JSON.stringify({
      origin: 'Kigali, Rwanda',
      destination: 'Nairobi, Kenya',
      serviceType: 'AIR',
      commodity: 'General cargo',
      chargeableWeightKg: 25,
      volumeCbm: 0.15,
      packages: 2,
      company: 'AAL load test',
      contactName: 'Load Test',
      email: __ENV.TEST_EMAIL || 'loadtest@example.invalid',
      phone: '+250700000000',
    }),
    { headers: { 'Content-Type': 'application/json' } },
  );

  check(response, {
    'quote request returns 200': (r) => r.status === 200,
    'quote request token returned': (r) => {
      try { return Boolean(r.json('requestToken')); } catch { return false; }
    },
  });
}
