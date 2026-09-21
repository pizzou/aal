const API_BASE = (
  process.env.NEXT_PUBLIC_API_BASE_URL ||
  (process.env.NODE_ENV === "production"
    ? "https://aal-ocst.onrender.com"
    : "http://localhost:8080")
).replace(/\/+$/, "");

const ACCESS_TOKEN_STORAGE_KEY = "aal.access-token";
const AUTH_EXPIRED_EVENT = "aal:auth-expired";

let csrfToken: string | null = null;
let csrfRequest: Promise<string> | null = null;
let accessToken: string | null = null;

function readStoredAccessToken(): string | null {
  if (accessToken) return accessToken;
  if (typeof window === "undefined") return null;

  try {
    const stored = window.sessionStorage.getItem(ACCESS_TOKEN_STORAGE_KEY);
    accessToken = stored && stored.trim() ? stored : null;
    return accessToken;
  } catch {
    return null;
  }
}

export function setAccessToken(token: string | null): void {
  accessToken = token && token.trim() ? token.trim() : null;

  if (typeof window === "undefined") return;

  try {
    if (accessToken) {
      window.sessionStorage.setItem(ACCESS_TOKEN_STORAGE_KEY, accessToken);
    } else {
      window.sessionStorage.removeItem(ACCESS_TOKEN_STORAGE_KEY);
    }
  } catch {
    // Session storage can be unavailable in privacy-restricted browsers.
  }
}

export function getAccessToken(): string | null {
  return readStoredAccessToken();
}

export function clearAccessToken(): void {
  setAccessToken(null);
}

function notifyAuthenticationExpired(): void {
  clearAccessToken();

  if (typeof window !== "undefined") {
    window.dispatchEvent(new CustomEvent(AUTH_EXPIRED_EVENT));
  }
}

export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  size?: number;
  number?: number;
  numberOfElements?: number;
  first?: boolean;
  last?: boolean;
  empty?: boolean;
}

async function ensureCsrf(forceRefresh = false): Promise<string> {
  if (!forceRefresh && csrfToken) return csrfToken;

  if (csrfRequest) return csrfRequest;

  if (forceRefresh) csrfToken = null;

  const request = fetch(`${API_BASE}/api/auth/csrf`, {
    credentials: "include",
    cache: "no-store",
  })
    .then(async (response) => {
      if (!response.ok) {
        throw new ApiError(
          response.status,
          "Unable to establish CSRF protection",
        );
      }

      const body = (await response.json()) as { token?: string };
      if (!body.token) {
        throw new ApiError(
          response.status,
          "CSRF token was not returned by the server",
        );
      }

      csrfToken = body.token;
      return body.token;
    })
    .finally(() => {
      csrfRequest = null;
    });

  csrfRequest = request;
  return request;
}

async function extractErrorMessage(
  response: Response,
  fallback: string,
): Promise<string> {
  try {
    const body = (await response.json()) as {
      error?: string;
      message?: string;
      detail?: string;
    };
    if (body?.error) return body.error;
    if (body?.message) return body.message;
    if (body?.detail) return body.detail;
  } catch {
    // Ignore non-JSON responses.
  }
  return fallback;
}

function isCsrfFailure(status: number, message: string): boolean {
  return status === 403 && /csrf/i.test(message);
}

function applyAuthenticationHeader(headers: Headers): void {
  if (headers.has("Authorization")) return;

  const token = readStoredAccessToken();
  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }
}

export async function apiFetch<T>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  const method = (options.method ?? "GET").toUpperCase();
  const headers = new Headers(options.headers);

  if (
    options.body !== undefined &&
    !(options.body instanceof FormData) &&
    !headers.has("Content-Type")
  ) {
    headers.set("Content-Type", "application/json");
  }

  applyAuthenticationHeader(headers);

  const mutating = ["POST", "PUT", "PATCH", "DELETE"].includes(method);
  if (mutating) headers.set("X-CSRF-Token", await ensureCsrf());

  const request = () =>
    fetch(`${API_BASE}${path}`, {
      ...options,
      credentials: "include",
      headers,
    });

  let response = await request();

  if (!response.ok) {
    let message = await extractErrorMessage(
      response,
      `Request failed with status ${response.status}`,
    );

    if (mutating && isCsrfFailure(response.status, message)) {
      csrfToken = null;
      headers.set("X-CSRF-Token", await ensureCsrf(true));
      response = await request();
      if (!response.ok) {
        message = await extractErrorMessage(
          response,
          `Request failed with status ${response.status}`,
        );
      }
    }

    if (!response.ok) {
      if (response.status === 401) {
        csrfToken = null;
        notifyAuthenticationExpired();
      }
      throw new ApiError(response.status, message);
    }
  }

  if (response.status === 204) return undefined as T;

  const contentType = response.headers.get("content-type") ?? "";
  if (!contentType.includes("application/json")) return undefined as T;

  return (await response.json()) as T;
}

export async function apiUpload<T>(path: string, file: File): Promise<T> {
  const form = new FormData();
  form.append("file", file);

  const request = (token: string) => {
    const headers = new Headers({ "X-CSRF-Token": token });
    applyAuthenticationHeader(headers);

    return fetch(`${API_BASE}${path}`, {
      method: "POST",
      credentials: "include",
      headers,
      body: form,
    });
  };

  let token = await ensureCsrf();
  let response = await request(token);

  if (!response.ok) {
    let message = await extractErrorMessage(
      response,
      `Upload failed with status ${response.status}`,
    );

    if (isCsrfFailure(response.status, message)) {
      csrfToken = null;
      token = await ensureCsrf(true);
      response = await request(token);
      if (!response.ok) {
        message = await extractErrorMessage(
          response,
          `Upload failed with status ${response.status}`,
        );
      }
    }

    if (!response.ok) {
      if (response.status === 401) {
        csrfToken = null;
        notifyAuthenticationExpired();
      }
      throw new ApiError(response.status, message);
    }
  }

  if (response.status === 204) return undefined as T;

  const contentType = response.headers.get("content-type") ?? "";
  if (!contentType.includes("application/json")) return undefined as T;

  return (await response.json()) as T;
}

export interface AuthLoginResponse {
  accessToken: string | null;
  tenantId: string;
  userId: string;
  role: string;
  mustChangePassword: boolean;
  otpRequired: boolean;
  otpChallengeToken?: string | null;
}

export interface AuthSessionResponse {
  tenantId: string;
  userId: string;
  role: string;
  mustChangePassword: boolean;
}

export const authApi = {
  login: (
    email: string,
    password: string,
    otp?: string,
    otpChallengeToken?: string,
  ) =>
    apiFetch<AuthLoginResponse>("/api/auth/login", {
      method: "POST",
      body: JSON.stringify({
        email: email.trim().toLowerCase(),
        password,
        otp,
        otpChallengeToken,
      }),
    }),
  sendLoginOtp: (otpChallengeToken: string) =>
    apiFetch<void>("/api/auth/send-login-otp", {
      method: "POST",
      body: JSON.stringify({ otpChallengeToken }),
    }),

  session: () => apiFetch<AuthSessionResponse>("/api/auth/session"),

  logout: () => apiFetch<void>("/api/auth/logout", { method: "POST" }),
  forgotPassword: (email: string) =>
    apiFetch<void>("/api/auth/password/forgot", {
      method: "POST",
      body: JSON.stringify({ email }),
    }),
  resetPassword: (token: string, newPassword: string) =>
    apiFetch<void>("/api/auth/password/reset", {
      method: "POST",
      body: JSON.stringify({ token, newPassword }),
    }),
};

export interface UserNotificationRecord {
  id: string;
  type: string;
  title: string;
  message: string;
  link?: string | null;
  read: boolean;
  createdAt: string;
}
export const userNotificationsApi = {
  list: () =>
    apiFetch<{ content: UserNotificationRecord[]; totalElements: number }>(
      "/api/notifications?page=0&size=50",
    ),
  unreadCount: () => apiFetch<number>("/api/notifications/unread-count"),
  markRead: (id: string) =>
    apiFetch<void>(`/api/notifications/${encodeURIComponent(id)}/read`, {
      method: "POST",
    }),
};

export interface NotificationRecord {
  id: string;
  recipient: string | null;
  subject: string;
  status: "SENT" | "LOGGED_ONLY" | "FAILED";
  errorDetail: string | null;
  createdAt: string;
}

export interface Shipment {
  id: string;
  referenceCode: string;
  originAddress: string;
  destinationAddress: string;

  status:
    | "PENDING"
    | "PLANNING"
    | "BOOKED"
    | "PICKED_UP"
    | "DEPARTED"
    | "IN_TRANSIT"
    | "ARRIVED"
    | "CUSTOMS"
    | "OUT_FOR_DELIVERY"
    | "DELIVERED"
    | "COMPLETED"
    | "ON_HOLD"
    | "CANCELLED";

  transportMode:
    | "ROAD"
    | "AIR"
    | "SEA"
    | "RAIL"
    | "INLAND_WATERWAY"
    | "COURIER"
    | "LAST_MILE"
    | "RORO"
    | "PROJECT_CARGO";

  carrierName: string | null;
  carrierReferenceNumber: string | null;
  trackingToken: string;
  weightKg: number | null;
  notificationEmail: string | null;
  flightNumber: string | null;
  dateOpened: string | null;

  clientName: string | null;
  contact: string | null;
  commodity: string | null;

  originCountry: string | null;
  originCityPort: string | null;
  destinationCountry: string | null;
  destinationCityPort: string | null;

  grossWeightKg: number | null;
  volumetricWeightKg: number | null;
  chargeableWeightKg: number | null;
  packages: number | null;

  airlineUsed: string | null;
  serviceType: string | null;
  operatorName: string | null;

  etd: string | null;
  eta: string | null;

  supplierCost: number | null;
  otherCost: number | null;
  totalCost: number | null;

  amountBilledToClient: number | null;
  amountPaidByClient: number | null;
  amountRemaining: number | null;

  otherExpenses: number | null;
  amountPaidToSupply: number | null;
  netIncome: number | null;
  grossProfit: number | null;
  marginPercent: number | null;

  invoiceNo: string | null;
  paymentStatus: string | null;

  ownerName: string | null;
  nextAction: string | null;
  nextActionDate: string | null;

  notes: string | null;
  currency: string | null;

  createdAt: string;
  updatedAt: string;
}

export interface TrackingEvent {
  id: string;
  eventType: string;
  location: string | null;
  notes: string | null;
  occurredAt: string;
}

export interface ShipmentListParams {
  page?: number;
  size?: number;
  q?: string;
  mode?: string;
  status?: string;
}

export const shipmentsApi = {
  list: (params: ShipmentListParams = {}) => {
    const query = new URLSearchParams();

    if (params.page != null)
      query.set("page", String(Math.max(0, params.page)));
    if (params.size != null)
      query.set("size", String(Math.max(1, params.size)));

    const search = params.q?.trim();
    if (search) query.set("q", search);

    const mode = params.mode?.trim();
    if (mode && mode.toUpperCase() !== "ALL") query.set("mode", mode);

    const status = params.status?.trim();
    if (status && status.toUpperCase() !== "ALL") query.set("status", status);

    query.append("sort", "dateOpened,desc");
    query.append("sort", "createdAt,desc");

    const encodedQuery = query.toString();
    return apiFetch<Page<Shipment>>(
      `/api/shipments${encodedQuery ? `?${encodedQuery}` : ""}`,
    );
  },

  get: (id: string) => apiFetch<Shipment>(`/api/shipments/${id}`),

  create: (data: {
    referenceCode: string;
    originAddress: string;
    destinationAddress: string;
    transportMode: string;
    carrierName?: string;
    carrierReferenceNumber?: string;
  }) =>
    apiFetch<Shipment>("/api/shipments", {
      method: "POST",
      body: JSON.stringify(data),
    }),

  updateStatus: (id: string, status: string) =>
    apiFetch<Shipment>(`/api/shipments/${id}/status`, {
      method: "PATCH",
      body: JSON.stringify({ status }),
    }),

  updateWeight: (id: string, weightKg: number) =>
    apiFetch<Shipment>(`/api/shipments/${id}/weight`, {
      method: "PATCH",
      body: JSON.stringify({ weightKg }),
    }),

  updateNotificationEmail: (id: string, notificationEmail: string) =>
    apiFetch<Shipment>(`/api/shipments/${id}/notification-email`, {
      method: "PATCH",
      body: JSON.stringify({ notificationEmail }),
    }),

  updateFlightNumber: (id: string, flightNumber: string) =>
    apiFetch<Shipment>(`/api/shipments/${id}/flight-number`, {
      method: "PATCH",
      body: JSON.stringify({ flightNumber }),
    }),

  notificationHistory: (id: string) =>
    apiFetch<Page<NotificationRecord>>(`/api/shipments/${id}/notifications`),

  trackingHistory: (id: string) =>
    apiFetch<TrackingEvent[]>(`/api/shipments/${id}/events`),

  addTrackingEvent: (
    id: string,
    data: {
      eventType: string;
      location?: string;
      notes?: string;
    },
  ) =>
    apiFetch<TrackingEvent>(`/api/shipments/${id}/events`, {
      method: "POST",
      body: JSON.stringify(data),
    }),
};

export interface PublicTrackingEvent {
  eventType: string;
  location: string | null;
  notes: string | null;
  occurredAt: string;
}

export interface PublicShipmentView {
  referenceCode: string;
  originAddress: string;
  destinationAddress: string;
  status: string;
  transportMode: string;
  carrierName: string | null;
  carrierReferenceNumber: string | null;
  eta: string | null;
  events: PublicTrackingEvent[];
  documents: Array<{ documentType: string; status: string; createdAt: string }>;
  pod: {
    recipientName: string | null;
    deliveredAt: string;
    evidenceAvailable: boolean;
  } | null;
}

export type PublicQuoteView = {
  shareId: string;
  quoteReference: string;
  quoteDate: string;
  client?: string | null;
  route?: string | null;
  serviceType?: string | null;
  commodity?: string | null;
  chargeableWeightKg?: number | null;
  quotedAmount?: number | null;
  currency?: string | null;
  quoteVersionId?: string | null;
  validUntil?: string | null;
  status: string;
  actionable: boolean;
  response?: string | null;
};

export const publicQuoteApi = {
  view: (token: string) =>
    apiFetch<PublicQuoteView>(
      `/api/public/quotes/${encodeURIComponent(token)}`,
    ),
  respond: (token: string, action: "ACCEPTED" | "DECLINED") =>
    apiFetch<{ status: string; message: string }>(
      `/api/public/quotes/${encodeURIComponent(token)}/response?action=${action}`,
      { method: "POST" },
    ),
  book: (token: string, shipmentReference?: string) =>
    apiFetch<{
      shipmentId: string;
      reference: string;
      trackingToken: string;
      status: string;
      message: string;
    }>(
      `/api/public/quotes/${encodeURIComponent(token)}/book${shipmentReference ? `?shipmentReference=${encodeURIComponent(shipmentReference)}` : ""}`,
      { method: "POST" },
    ),
};

export interface PublicQuoteOption {
  mode: string;
  modeLabel: string;
  baseCharge: number;
  fuelSurcharge: number;
  totalCharge: number;
  currency: string;
  rateType: string;
  available: boolean;
  validUntil: string;
}

export interface PublicQuoteRequestResponse {
  requestToken: string;
  quoteReference: string;
  createdAt: string;
  validUntil: string;
  origin: string;
  destination: string;
  company: string | null;
  contactName: string;
  email: string;
  phone: string | null;
  commodity: string | null;
  packages: number | null;
  volumeCbm: number | null;
  options: PublicQuoteOption[];
}

export const publicCommercialApi = {
  requestQuote: (data: Record<string, unknown>) =>
    apiFetch<PublicQuoteRequestResponse>("/api/public/commercial/quotes", {
      method: "POST",
      body: JSON.stringify(data),
    }),
  quoteRequest: (token: string) =>
    apiFetch<PublicQuoteRequestResponse>(
      `/api/public/commercial/quote-requests/${encodeURIComponent(token)}`,
    ),
  bookShipment: (data: Record<string, unknown>) =>
    apiFetch<{
      shipmentId: string;
      reference: string;
      trackingToken: string;
      status: string;
      message: string;
    }>("/api/public/commercial/bookings", {
      method: "POST",
      body: JSON.stringify(data),
    }),
};

export const publicTrackingApi = {
  track: (token: string) =>
    apiFetch<PublicShipmentView>(
      `/api/public/tracking/${encodeURIComponent(token)}`,
    ),
  feedback: (token: string, data: Record<string, unknown>) =>
    apiFetch<{ id: string; status: string }>(
      `/api/public/tracking/${encodeURIComponent(token)}/feedback`,
      { method: "POST", body: JSON.stringify(data) },
    ),
};

/*

* ============================================================================
* AAL COMMAND CENTER
* ============================================================================
  */

export const commandCenterApi = {
  updateShipment: (id: string, data: Record<string, unknown>) =>
    apiFetch<Shipment>(`/api/shipments/${id}/command-center`, {
      method: "PATCH",
      body: JSON.stringify(data),
    }),
  dashboard: (asOf?: string) =>
    apiFetch<ManagementReport>(
      `/api/command-center/dashboard${asOf ? `?asOf=${encodeURIComponent(asOf)}` : ""}`,
    ),
  importExcel: (file: File) =>
    apiUpload<Record<string, number>>("/api/command-center/import/excel", file),
  dailyOperations: (date: string) =>
    apiFetch<DailyOperationsResponse>(
      `/api/command-center/daily-operations?date=${encodeURIComponent(date)}`,
    ),
  advanced: (asOf?: string) =>
    apiFetch<AdvancedDashboard>(
      `/api/command-center/advanced${asOf ? `?asOf=${encodeURIComponent(asOf)}` : ""}`,
    ),
};

export interface Vehicle {
  id: string;
  registrationNumber: string;
  vehicleType: string;
  capacityKg: number;
  status: string;
  createdAt: string;
}

export const vehiclesApi = {
  list: () => apiFetch<Vehicle[]>("/api/vehicles"),

  create: (data: {
    registrationNumber: string;
    vehicleType: string;
    capacityKg: number;
  }) =>
    apiFetch<Vehicle>("/api/vehicles", {
      method: "POST",
      body: JSON.stringify(data),
    }),
};

export interface Driver {
  id: string;
  fullName: string;
  licenseNumber: string;
  phone: string | null;
  status: string;
  createdAt: string;
}

export const driversApi = {
  list: () => apiFetch<Driver[]>("/api/drivers"),

  create: (data: { fullName: string; licenseNumber: string; phone?: string }) =>
    apiFetch<Driver>("/api/drivers", {
      method: "POST",
      body: JSON.stringify(data),
    }),
};

export interface Trip {
  id: string;
  vehicleId: string;
  driverId: string;
  originAddress: string;
  destinationAddress: string;
  status: string;
  scheduledDeparture: string | null;
  actualDeparture: string | null;
  actualArrival: string | null;
  shipmentIds: string[];
}

export const tripsApi = {
  list: () => apiFetch<Page<Trip>>("/api/trips"),

  get: (id: string) => apiFetch<Trip>(`/api/trips/${id}`),

  create: (data: {
    vehicleId: string;
    driverId: string;
    originAddress: string;
    destinationAddress: string;
    scheduledDeparture?: string;
    shipmentIds?: string[];
  }) =>
    apiFetch<Trip>("/api/trips", {
      method: "POST",
      body: JSON.stringify(data),
    }),

  start: (id: string) =>
    apiFetch<Trip>(`/api/trips/${id}/start`, {
      method: "POST",
    }),

  complete: (id: string) =>
    apiFetch<Trip>(`/api/trips/${id}/complete`, {
      method: "POST",
    }),

  cancel: (id: string) =>
    apiFetch<Trip>(`/api/trips/${id}/cancel`, {
      method: "POST",
    }),
};

export interface GpsPosition {
  vehicleId: string;
  latitude: number;
  longitude: number;
  speedKmh: number | null;
  headingDegrees: number | null;
  recordedAt: string;
  source?: string;
  deviceId?: string | null;
  accuracyMeters?: number | null;
  batteryPercent?: number | null;
}

export interface GpsStatus {
  vehicleId: string;
  latest: GpsPosition | null;
  source: string;
  health: string;
  stale: boolean;
  ageSeconds: number;
  checkedAt: string;
}

export interface TraccarDevice {
  id: string;
  vehicleId: string;
  provider: string;
  externalDeviceId: string;
  enabled: boolean;
}

export const gpsApi = {
  recordPosition: (
    vehicleId: string,
    data: {
      latitude: number;
      longitude: number;
      speedKmh?: number;
      headingDegrees?: number;
      recordedAt?: string;
      source?: string;
      deviceId?: string;
      accuracyMeters?: number;
      batteryPercent?: number;
    },
  ) =>
    apiFetch<GpsPosition>(`/api/vehicles/${vehicleId}/gps`, {
      method: "POST",
      body: JSON.stringify(data),
    }),

  latest: (vehicleId: string) =>
    apiFetch<GpsPosition>(`/api/vehicles/${vehicleId}/gps/latest`),

  status: (vehicleId: string) =>
    apiFetch<GpsStatus>(`/api/vehicles/${vehicleId}/gps/status`),

  history: (vehicleId: string) =>
    apiFetch<Page<GpsPosition>>(`/api/vehicles/${vehicleId}/gps/history`),
};

export const traccarGpsApi = {
  device: (vehicleId: string) =>
    apiFetch<TraccarDevice>(`/api/vehicles/${vehicleId}/gps/traccar/device`),

  saveDevice: (
    vehicleId: string,
    data: { provider?: string; externalDeviceId: string; enabled: boolean },
  ) =>
    apiFetch<TraccarDevice>(`/api/vehicles/${vehicleId}/gps/traccar/device`, {
      method: "POST",
      body: JSON.stringify({ ...data, provider: "TRACCAR" }),
    }),

  sync: (vehicleId: string) =>
    apiFetch<GpsPosition>(`/api/vehicles/${vehicleId}/gps/traccar/sync`, {
      method: "POST",
    }),
};

export interface PlannedShipment {
  shipmentId: string;
  referenceCode: string;
  weightKg: number;
}

export interface LoadPlan {
  vehicleId: string;
  vehicleCapacityKg: number;
  totalWeightLoadedKg: number;
  utilizationPercent: number;
  selected: PlannedShipment[];
  excluded: PlannedShipment[];
}

export const loadPlanningApi = {
  plan: (vehicleId: string) =>
    apiFetch<LoadPlan>(`/api/vehicles/${vehicleId}/load-plan`),
};

export interface Warehouse {
  id: string;
  name: string;
  address: string;
  createdAt: string;
}

export const warehousesApi = {
  list: () => apiFetch<Warehouse[]>("/api/warehouses"),

  create: (data: { name: string; address: string }) =>
    apiFetch<Warehouse>("/api/warehouses", {
      method: "POST",
      body: JSON.stringify(data),
    }),
};

export interface InventoryItem {
  id: string;
  warehouseId: string;
  sku: string;
  name: string;
  quantityOnHand: number;
  reorderLevel: number;
  belowReorderLevel: boolean;
  updatedAt: string;
}

export interface StockMovementRecord {
  id: string;
  movementType: string;
  quantity: number;
  reason: string | null;
  createdAt: string;
}

export const inventoryApi = {
  create: (
    warehouseId: string,
    data: {
      sku: string;
      name: string;
      reorderLevel: number;
    },
  ) =>
    apiFetch<InventoryItem>(`/api/warehouses/${warehouseId}/inventory`, {
      method: "POST",
      body: JSON.stringify(data),
    }),

  list: (warehouseId: string) =>
    apiFetch<Page<InventoryItem>>(`/api/warehouses/${warehouseId}/inventory`),

  get: (itemId: string) => apiFetch<InventoryItem>(`/api/inventory/${itemId}`),

  recordMovement: (
    itemId: string,
    data: {
      movementType: string;
      quantity: number;
      reason?: string;
    },
  ) =>
    apiFetch<InventoryItem>(`/api/inventory/${itemId}/movements`, {
      method: "POST",
      body: JSON.stringify(data),
    }),

  history: (itemId: string) =>
    apiFetch<Page<StockMovementRecord>>(`/api/inventory/${itemId}/movements`),
};

export interface SensorReading {
  id: string;
  temperatureCelsius: number | null;
  humidityPercent: number | null;
  recordedAt: string;
  violatesThreshold: boolean;
}

export interface SensorThreshold {
  minTemperatureCelsius: number | null;
  maxTemperatureCelsius: number | null;
  minHumidityPercent: number | null;
  maxHumidityPercent: number | null;
}

export const sensorsApi = {
  recordReading: (
    shipmentId: string,
    data: {
      temperatureCelsius?: number;
      humidityPercent?: number;
      recordedAt?: string;
      eventId?: string;
    },
  ) =>
    apiFetch<SensorReading>(`/api/shipments/${shipmentId}/sensors/readings`, {
      method: "POST",
      body: JSON.stringify(data),
    }),

  history: (shipmentId: string) =>
    apiFetch<Page<SensorReading>>(
      `/api/shipments/${shipmentId}/sensors/readings`,
    ),

  setThreshold: (
    shipmentId: string,
    data: {
      minTemperatureCelsius?: number;
      maxTemperatureCelsius?: number;
      minHumidityPercent?: number;
      maxHumidityPercent?: number;
    },
  ) =>
    apiFetch<SensorThreshold>(
      `/api/shipments/${shipmentId}/sensors/threshold`,
      {
        method: "PUT",
        body: JSON.stringify(data),
      },
    ),
};

export interface FlightStatusResult {
  flightStatus: string;
  departureDelayMinutes: number;
  arrivalDelayMinutes: number;
  significantDelay: boolean;
}

export const flightStatusApi = {
  check: (shipmentId: string) =>
    apiFetch<FlightStatusResult>(`/api/shipments/${shipmentId}/flight-status`),
};

export function awbDownloadUrl(shipmentId: string): string {
  return `${API_BASE}/api/shipments/${shipmentId}/documents/awb`;
}

export const logisticsApi = {
  capabilities: () =>
    apiFetch<Record<string, unknown>>("/api/universal/capabilities"),

  cargo: (shipmentId: string) =>
    apiFetch<Record<string, unknown>[]>(
      `/api/universal/shipments/${shipmentId}/cargo`,
    ),

  addCargo: (shipmentId: string, data: Record<string, unknown>) =>
    apiFetch<Record<string, unknown>>(
      `/api/universal/shipments/${shipmentId}/cargo`,
      {
        method: "POST",
        body: JSON.stringify(data),
      },
    ),

  legs: (shipmentId: string) =>
    apiFetch<Record<string, unknown>[]>(
      `/api/universal/shipments/${shipmentId}/legs`,
    ),

  addLeg: (shipmentId: string, data: Record<string, unknown>) =>
    apiFetch<Record<string, unknown>>(
      `/api/universal/shipments/${shipmentId}/legs`,
      {
        method: "POST",
        body: JSON.stringify(data),
      },
    ),

  oceanVoyages: () => apiFetch<Record<string, unknown>[]>("/api/ocean/voyages"),

  createOceanVoyage: (data: Record<string, unknown>) =>
    apiFetch<Record<string, unknown>>("/api/ocean/voyages", {
      method: "POST",
      body: JSON.stringify(data),
    }),

  oceanContainers: (shipmentId: string) =>
    apiFetch<Record<string, unknown>[]>(
      `/api/ocean/shipments/${shipmentId}/containers`,
    ),

  createOceanContainer: (shipmentId: string, data: Record<string, unknown>) =>
    apiFetch<Record<string, unknown>>(
      `/api/ocean/shipments/${shipmentId}/containers`,
      {
        method: "POST",
        body: JSON.stringify(data),
      },
    ),

  oceanBookings: () =>
    apiFetch<Record<string, unknown>[]>("/api/ocean/bookings"),

  createOceanBooking: (data: Record<string, unknown>) =>
    apiFetch<Record<string, unknown>>("/api/ocean/bookings", {
      method: "POST",
      body: JSON.stringify(data),
    }),

  roadConsignments: () =>
    apiFetch<Record<string, unknown>[]>("/api/road/consignments"),

  createRoadConsignment: (shipmentId: string, data: Record<string, unknown>) =>
    apiFetch<Record<string, unknown>>(
      `/api/road/shipments/${shipmentId}/consignment`,
      {
        method: "POST",
        body: JSON.stringify(data),
      },
    ),

  railConsignments: () =>
    apiFetch<Record<string, unknown>[]>("/api/rail/consignments"),

  createRailConsignment: (shipmentId: string, data: Record<string, unknown>) =>
    apiFetch<Record<string, unknown>>(
      `/api/rail/shipments/${shipmentId}/consignment`,
      {
        method: "POST",
        body: JSON.stringify(data),
      },
    ),
};

/*

* ============================================================================
* AIR CARGO
* ============================================================================
  */

export type AirCargoFlight = {
  id: string;
  carrierCode: string;
  carrierName: string;
  flightNumber: string;
  origin: string;
  destination: string;
  departure: string;
  arrival?: string | null;
  totalCapacityKg: number;
  availableCapacityKg: number;
  status: string;
  source: string;
};
export type RouteOption = {
  flightId: string;
  carrierCode: string;
  carrierName: string;
  flightNumber: string;
  origin: string;
  destination: string;
  departure: string;
  arrival?: string | null;
  availableCapacityKg: number;
  score: number;
};
export type AirCargoBookingResponse = {
  id: string;
  status: string;
  confirmationNumber?: string | null;
  providerReference?: string | null;
  flightNumber: string;
  requestedWeightKg: number;
};

export const airCargoApi = {
  shipmentDocuments: (shipmentId: string) =>
    apiFetch<Record<string, unknown>[]>(
      `/api/air-cargo/documents/shipment/${shipmentId}`,
    ),

  searchFlights: (data: {
    origin: string;
    destination: string;
    from?: string;
    to?: string;
    weightKg: number;
  }) =>
    apiFetch<AirCargoFlight[]>("/api/air-cargo/flights/search", {
      method: "POST",
      body: JSON.stringify(data),
    }),

  capacity: (flightNumber: string, date: string) =>
    apiFetch<any>(
      `/api/air-cargo/capacity?flightNumber=${encodeURIComponent(
        flightNumber,
      )}&date=${encodeURIComponent(date)}`,
    ),

  book: (data: Record<string, unknown>) =>
    apiFetch<AirCargoBookingResponse>("/api/air-cargo/bookings", {
      method: "POST",
      body: JSON.stringify(data),
    }),

  optimizeRoutes: (data: {
    origin: string;
    destination: string;
    from?: string;
    to?: string;
    weightKg: number;
  }) =>
    apiFetch<RouteOption[]>("/api/air-cargo/routes/optimize", {
      method: "POST",
      body: JSON.stringify(data),
    }),
};

export type DispatchStop = {
  id: string;
  tripId: string;
  shipmentId?: string | null;
  sequenceNo: number;
  stopType: string;
  address: string;
  latitude?: number | null;
  longitude?: number | null;
  plannedAt?: string | null;
  eta?: string | null;
  actualAt?: string | null;
  status: string;
  distanceFromPreviousKm?: number | null;
  plannedDurationMinutes?: number | null;
  notes?: string | null;
};
export type OperationalException = {
  id: string;
  shipmentId?: string | null;
  tripId?: string | null;
  vehicleId?: string | null;
  type: string;
  severity: string;
  status: string;
  owner?: string | null;
  description: string;
  actionTaken?: string | null;
  resolution?: string | null;
  createdAt: string;
  resolvedAt?: string | null;
};
export type WarehouseTask = {
  id: string;
  warehouseId: string;
  shipmentId?: string | null;
  inventoryItemId?: string | null;
  taskType: string;
  status: string;
  quantity?: number | null;
  sourceLocation?: string | null;
  destinationLocation?: string | null;
  assignedTo?: string | null;
  dueAt?: string | null;
  completedAt?: string | null;
  notes?: string | null;
};
export type CarrierTender = {
  id: string;
  shipmentId?: string | null;
  tripId?: string | null;
  tenderKey: string;
  carrierName: string;
  carrierContact?: string | null;
  quotedAmount?: number | null;
  currency: string;
  status: string;
  expiresAt?: string | null;
  responseNote?: string | null;
  createdAt: string;
  respondedAt?: string | null;
};
export type FleetLive = {
  vehicleId: string;
  registrationNumber: string;
  vehicleStatus: string;
  driverId?: string | null;
  driverName?: string | null;
  tripId?: string | null;
  tripStatus?: string | null;
  latitude?: number | null;
  longitude?: number | null;
  speedKmh?: number | null;
  recordedAt?: string | null;
};
export type QuoteRecord = {
  id: string;
  quoteId: string;
  quoteDate: string;
  client?: string | null;
  route?: string | null;
  serviceType?: string | null;
  commodity?: string | null;
  chargeableWeightKg?: number | null;
  supplierCost?: number | null;
  otherCost?: number | null;
  markupPercent?: number | null;
  quotedAmount?: number | null;
  expectedProfit?: number | null;
  validUntil?: string | null;
  status: string;
  owner?: string | null;
  followUpDate?: string | null;
  notes?: string | null;
  pricingMode?: string | null;
  createdAt: string;
  currency?: string | null;
  incoterm?: string | null;
  taxRate?: number | null;
  taxAmount?: number | null;
  customsCost?: number | null;
  insuranceCost?: number | null;
  customerCreditTerms?: string | null;
  lockedAmount?: number | null;
  lockedCurrency?: string | null;
  priceLockedAt?: string | null;
  acceptedVersionId?: string | null;
};
export type InvoiceRecord = {
  id: string;
  invoiceNo: string;
  issueDate: string;
  client?: string | null;
  shipmentId?: string | null;
  currency: string;
  invoiceAmount: number;
  amountPaid: number;
  balance: number;
  dueDate?: string | null;
  lastFollowUp?: string | null;
  nextFollowUp?: string | null;
  owner?: string | null;
  notes?: string | null;
  daysOverdue: number;
  agingBucket: string;
  status: string;
};
export type ClientRecord = {
  id: string;
  clientId: string;
  clientCompany?: string | null;
  contactPerson?: string | null;
  phone?: string | null;
  email?: string | null;
  industry?: string | null;
  country?: string | null;
  city?: string | null;
  leadSource?: string | null;
  clientStatus?: string | null;
  relationshipOwner?: string | null;
  nextFollowUp?: string | null;
  notes?: string | null;
};
export type PartnerRecord = {
  id: string;
  partnerId: string;
  country?: string | null;
  company?: string | null;
  contactPerson?: string | null;
  phone?: string | null;
  email?: string | null;
  services?: string | null;
  cityPortAirport?: string | null;
  paymentTerms?: string | null;
  rating?: number | null;
  status?: string | null;
  lastVerified?: string | null;
  notes?: string | null;
};
export type TaskRecord = {
  id: string;
  taskId: string;
  createdDate: string;
  department?: string | null;
  relatedReference?: string | null;
  task: string;
  priority?: string | null;
  owner?: string | null;
  dueDate?: string | null;
  status?: string | null;
  completionDate?: string | null;
  notes?: string | null;
  daysRemaining: number;
  overdue: boolean;
};
export type ExpenseRecord = {
  id: string;
  expenseId: string;
  expenseDate: string;
  type?: string | null;
  category?: string | null;
  shipmentId?: string | null;
  client?: string | null;
  vendorPayee?: string | null;
  description?: string | null;
  currency: string;
  originalAmount: number;
  exchangeRateToUsd?: number | null;
  usdEquivalent?: number | null;
  paymentMethod?: string | null;
  status?: string | null;
  approvedBy?: string | null;
};

export const operationsApi = {
  stops: (tripId: string) =>
    apiFetch<DispatchStop[]>(`/api/operations/trips/${tripId}/stops`),
  addStop: (data: Record<string, unknown>) =>
    apiFetch<DispatchStop>("/api/operations/stops", {
      method: "POST",
      body: JSON.stringify(data),
    }),
  arriveStop: (id: string) =>
    apiFetch<DispatchStop>(`/api/operations/stops/${id}/arrive`, {
      method: "POST",
    }),
  departStop: (id: string) =>
    apiFetch<DispatchStop>(`/api/operations/stops/${id}/depart`, {
      method: "POST",
    }),
  route: (tripId: string) =>
    apiFetch<{
      tripId: string;
      totalDistanceKm: number;
      totalDurationMinutes: number;
      stops: DispatchStop[];
    }>(`/api/operations/trips/${tripId}/route`),
  createPod: (data: Record<string, unknown>) =>
    apiFetch<Record<string, unknown>>("/api/operations/pod", {
      method: "POST",
      body: JSON.stringify(data),
    }),
  pod: (shipmentId: string) =>
    apiFetch<Record<string, unknown>>(
      `/api/operations/shipments/${shipmentId}/pod`,
    ),
  exceptions: (status?: string) =>
    apiFetch<OperationalException[]>(
      `/api/operations/exceptions${status ? `?status=${encodeURIComponent(status)}` : ""}`,
    ),
  openException: (data: Record<string, unknown>) =>
    apiFetch<OperationalException>("/api/operations/exceptions", {
      method: "POST",
      body: JSON.stringify(data),
    }),
  resolveException: (id: string, data: Record<string, unknown>) =>
    apiFetch<OperationalException>(`/api/operations/exceptions/${id}/resolve`, {
      method: "POST",
      body: JSON.stringify(data),
    }),
  warehouseTasks: (warehouseId: string, status?: string) =>
    apiFetch<WarehouseTask[]>(
      `/api/operations/warehouses/${warehouseId}/tasks${status ? `?status=${encodeURIComponent(status)}` : ""}`,
    ),
  createWarehouseTask: (data: Record<string, unknown>) =>
    apiFetch<WarehouseTask>("/api/operations/warehouse-tasks", {
      method: "POST",
      body: JSON.stringify(data),
    }),
  startWarehouseTask: (id: string) =>
    apiFetch<WarehouseTask>(`/api/operations/warehouse-tasks/${id}/start`, {
      method: "POST",
    }),
  completeWarehouseTask: (id: string) =>
    apiFetch<WarehouseTask>(`/api/operations/warehouse-tasks/${id}/complete`, {
      method: "POST",
    }),
  cancelWarehouseTask: (id: string) =>
    apiFetch<WarehouseTask>(`/api/operations/warehouse-tasks/${id}/cancel`, {
      method: "POST",
    }),
  tenders: () => apiFetch<CarrierTender[]>("/api/operations/tenders"),
  createTender: (data: Record<string, unknown>) =>
    apiFetch<CarrierTender>("/api/operations/tenders", {
      method: "POST",
      body: JSON.stringify(data),
    }),
  acceptTender: (id: string, note?: string) =>
    apiFetch<CarrierTender>(
      `/api/operations/tenders/${id}/accept${note ? `?note=${encodeURIComponent(note)}` : ""}`,
      { method: "POST" },
    ),
  rejectTender: (id: string, note?: string) =>
    apiFetch<CarrierTender>(
      `/api/operations/tenders/${id}/reject${note ? `?note=${encodeURIComponent(note)}` : ""}`,
      { method: "POST" },
    ),
  fleetLive: () => apiFetch<FleetLive[]>("/api/operations/fleet/live"),
};

export const commercialApi = {
  quotes: () => apiFetch<QuoteRecord[]>("/api/commercial/quotes"),
  createQuote: (data: Record<string, unknown>) =>
    apiFetch<QuoteRecord>("/api/commercial/quotes", {
      method: "POST",
      body: JSON.stringify(data),
    }),
  quoteStatus: (id: string, status: string) =>
    apiFetch<QuoteRecord>(
      `/api/commercial/quotes/${id}/status?status=${encodeURIComponent(status)}`,
      { method: "PATCH" },
    ),
  versions: (id: string) =>
    apiFetch<any[]>(`/api/commercial/quotes/${id}/versions`),
  createVersion: (id: string) =>
    apiFetch<any>(`/api/commercial/quotes/${id}/versions`, { method: "POST" }),
  lockVersion: (id: string, versionId: string) =>
    apiFetch<any>(`/api/commercial/quotes/${id}/versions/${versionId}/lock`, {
      method: "POST",
    }),
  approveVersion: (id: string, versionId: string) =>
    apiFetch<any>(
      `/api/commercial/quotes/${id}/versions/${versionId}/approve`,
      { method: "POST" },
    ),
  acceptVersion: (id: string, versionId: string) =>
    apiFetch<any>(`/api/commercial/quotes/${id}/versions/${versionId}/accept`, {
      method: "POST",
    }),
  shareQuote: (id: string, recipientEmail?: string) =>
    apiFetch<{
      shareId: string;
      quoteReference: string;
      recipientEmail: string;
      url: string;
      expiresAt: string;
    }>(
      `/api/commercial/quotes/${id}/share${
        recipientEmail
          ? `?recipientEmail=${encodeURIComponent(recipientEmail)}`
          : ""
      }`,
      { method: "POST" },
    ),
  invoices: () => apiFetch<InvoiceRecord[]>("/api/commercial/invoices"),
  createInvoice: (data: Record<string, unknown>) =>
    apiFetch<InvoiceRecord>("/api/commercial/invoices", {
      method: "POST",
      body: JSON.stringify(data),
    }),
  payInvoice: (id: string, data: Record<string, unknown>) =>
    apiFetch<InvoiceRecord>(`/api/commercial/invoices/${id}/payments`, {
      method: "POST",
      body: JSON.stringify(data),
    }),
  clients: () => apiFetch<ClientRecord[]>("/api/commercial/clients"),
  createClient: (data: Record<string, unknown>) =>
    apiFetch<ClientRecord>("/api/commercial/clients", {
      method: "POST",
      body: JSON.stringify(data),
    }),
  clientStatus: (id: string, status: string) =>
    apiFetch<ClientRecord>(
      `/api/commercial/clients/${id}/status?status=${encodeURIComponent(status)}`,
      { method: "PATCH" },
    ),
  partners: () => apiFetch<PartnerRecord[]>("/api/commercial/partners"),
  createPartner: (data: Record<string, unknown>) =>
    apiFetch<PartnerRecord>("/api/commercial/partners", {
      method: "POST",
      body: JSON.stringify(data),
    }),
  tasks: () => apiFetch<TaskRecord[]>("/api/commercial/tasks"),
  createTask: (data: Record<string, unknown>) =>
    apiFetch<TaskRecord>("/api/commercial/tasks", {
      method: "POST",
      body: JSON.stringify(data),
    }),
  taskStatus: (id: string, status: string) =>
    apiFetch<TaskRecord>(
      `/api/commercial/tasks/${id}/status?status=${encodeURIComponent(status)}`,
      { method: "PATCH" },
    ),
  expenses: () => apiFetch<ExpenseRecord[]>("/api/commercial/expenses"),
  createExpense: (data: Record<string, unknown>) =>
    apiFetch<ExpenseRecord>("/api/commercial/expenses", {
      method: "POST",
      body: JSON.stringify(data),
    }),
};

export const platformHealthApi = {
  current: () =>
    apiFetch<{
      status: string;
      healthy: boolean;
      checkedAt: string;
      components: Record<string, unknown>;
    }>("/api/platform/health"),
};

export function openOperationsEventStream(handlers: {
  onEvent?: (event: { type: string; data: unknown }) => void;
  onError?: () => void;
}) {
  const source = new EventSource(`${API_BASE}/api/operations/events`, {
    withCredentials: true,
  });

  const bind = (type: string) =>
    source.addEventListener(type, (event) => {
      try {
        handlers.onEvent?.({
          type,
          data: JSON.parse((event as MessageEvent).data),
        });
      } catch {
        handlers.onEvent?.({
          type,
          data: (event as MessageEvent).data,
        });
      }
    });

  ["connected", "gps", "milestone", "milestones-initialized"].forEach(bind);
  source.onerror = () => handlers.onError?.();

  return () => source.close();
}

export const enterprisePlatformApi = {
  carriers: () => apiFetch<any[]>("/api/platform/enterprise/carriers"),
  lanes: () => apiFetch<any[]>("/api/platform/enterprise/lanes"),
  locations: () => apiFetch<any[]>("/api/platform/enterprise/locations"),
  pricingRules: () => apiFetch<any[]>("/api/platform/enterprise/pricing-rules"),
  price: (data: Record<string, unknown>) =>
    apiFetch<any>("/api/platform/enterprise/pricing/quote", {
      method: "POST",
      body: JSON.stringify(data),
    }),
  convertLead: (clientId: string) =>
    apiFetch<any>(`/api/platform/enterprise/clients/${clientId}/convert-lead`, {
      method: "POST",
    }),
  clientActivities: (clientId: string) =>
    apiFetch<any[]>(`/api/platform/enterprise/clients/${clientId}/activities`),
  createActivity: (clientId: string, data: Record<string, unknown>) =>
    apiFetch<any>(`/api/platform/enterprise/clients/${clientId}/activities`, {
      method: "POST",
      body: JSON.stringify(data),
    }),
  customsWorkflows: () =>
    apiFetch<any[]>("/api/platform/enterprise/customs/workflows"),
};

export const auditApi = {
  list: (page = 0, size = 50) =>
    apiFetch<{
      content: Array<{
        id: string;
        userId: string | null;
        action: string;
        resourceType: string;
        resourceId: string | null;
        method: string;
        path: string;
        ipAddress: string | null;
        statusCode: number | null;
        success: boolean;
        createdAt: string;
      }>;
      totalElements: number;
    }>(`/api/audit?page=${page}&size=${size}`),
};

export type DailyOperationsResponse = {
  date: string;
  summary: {
    totalJobs: number;
    scheduledJobs: number;
    inTransitJobs: number;
    deliveredJobs: number;
    delayedJobs: number;
    exceptionJobs: number;
    unassignedJobs: number;
    routeCount: number;
  };
  fleet: Record<string, number>;
  jobs: Array<Record<string, unknown>>;
  routes: Array<Record<string, unknown>>;
  exceptions: Array<Record<string, unknown>>;
};

export type ManagementReport = {
  from: string;
  to: string;
  currency: string;
  mixedCurrencies: boolean;
  operations: {
    totalShipments: number;
    activeShipments: number;
    completedShipments: number;
    departedShipments: number;
    arrivalsToday: number;
    delayedShipments: number;
    exceptionShipments: number;
    unassignedShipments: number;
    onTimeRatePercent: number;
    completionRatePercent: number;
  };
  financial: {
    invoicedRevenue: number;
    collectedRevenue: number;
    outstandingReceivables: number;
    overdueReceivables: number;
    supplierCosts: number;
    otherCosts: number;
    grossProfit: number;
    grossMarginPercent: number;
    totalExpenses: number;
    netProfit: number;
    collectionRatePercent: number;
  };
  receivables: {
    invoiceCount: number;
    unpaidInvoices: number;
    partiallyPaidInvoices: number;
    overdueInvoices: number;
    invoiced: number;
    collected: number;
    outstanding: number;
    overdue: number;
    dueNext30Days: number;
    dueToday: number;
  };
  salesPipeline: {
    totalQuotes: number;
    openQuotes: number;
    wonQuotes: number;
    lostQuotes: number;
    expiredQuotes: number;
    quotedValue: number;
    wonValue: number;
    openValue: number;
    conversionRatePercent: number;
  };
  tasks: { openTasks: number; dueToday: number; overdueTasks: number };
  fleet: {
    vehicles: number;
    availableVehicles: number;
    onTripVehicles: number;
    maintenanceVehicles: number;
    drivers: number;
    availableDrivers: number;
    onTripDrivers: number;
  };
  customerProfitability: Array<{
    customer: string;
    shipments: number;
    revenue: number;
    supplierCost: number;
    otherCost: number;
    grossProfit: number;
    marginPercent: number;
  }>;
  carrierProfitability: Array<{
    carrier: string;
    shipments: number;
    revenue: number;
    supplierCost: number;
    otherCost: number;
    grossProfit: number;
    marginPercent: number;
    exceptionRatePercent: number;
  }>;
  shipmentProfitability: Array<{
    shipmentReference: string;
    dateOpened: string;
    customer: string;
    carrier: string;
    mode: string;
    currency: string;
    chargeableWeightKg: number;
    revenue: number;
    supplierCost: number;
    otherCost: number;
    grossProfit: number;
    marginPercent: number;
    status: string;
  }>;
  monthlyTrend: Array<{
    month: string;
    shipments: number;
    invoicedRevenue: number;
    collectedRevenue: number;
    outstandingReceivables: number;
    grossProfit: number;
  }>;
  receivablesAging: Array<{
    bucket: string;
    balance: number;
    invoiceCount: number;
  }>;
  quotationPipeline: Array<{
    status: string;
    count: number;
    quotedValue: number;
  }>;
  operationalExceptions: Array<{
    severity: string;
    type: string;
    reference: string;
    message: string;
    lane: string;
    mode: string;
  }>;
};

export type AdvancedDashboard = {
  asOf: string;
  operations: {
    totalShipments: number;
    activeShipments: number;
    deliveredShipments: number;
    delayedShipments: number;
    exceptionShipments: number;
    unassignedShipments: number;
    dueToday: number;
    onTimeRatePercent: number;
    completionRatePercent: number;
  };
  financial: {
    currency: string;
    mixedCurrencies: boolean;
    billed: number;
    collected: number;
    receivables: number;
    operatingCost: number;
    grossMargin: number;
    grossMarginPercent: number;
  };
  fleet: {
    totalVehicles: number;
    availableVehicles: number;
    onTripVehicles: number;
    maintenanceVehicles: number;
    vehicleUtilizationPercent: number;
    totalDrivers: number;
    availableDrivers: number;
    onTripDrivers: number;
    driverUtilizationPercent: number;
  };
  modeMix: Array<{ mode: string; shipments: number; sharePercent: number }>;
  statusMix: Array<{ status: string; shipments: number; sharePercent: number }>;
  trend: Array<{
    date: string;
    shipments: number;
    revenue: number;
    operatingCost: number;
  }>;
  topLanes: Array<{
    lane: string;
    shipments: number;
    delayedShipments: number;
  }>;
  exceptions: Array<{
    severity: string;
    type: string;
    reference: string;
    message: string;
    lane: string;
    mode: string;
  }>;
  actions: Array<{
    priority: string;
    title: string;
    detail: string;
    href: string;
  }>;
  operatingKpis: {
    activeShipments: number;
    revenueInvoiced: number;
    outstanding: number;
    grossProfit: number;
    overdueReceivables: number;
    openQuotations: number;
    dueNext30Days: number;
    overallProfitMargin: number;
    openTasks: number;
    overdueTasks: number;
    wonQuotations: number;
    salesWinRate: number;
  };
  monthlyFinancial: Array<{
    month: string;
    revenue: number;
    grossProfit: number;
  }>;
  receivablesAging: Array<{ bucket: string; balance: number }>;
  quotationStatus: Array<{ status: string; count: number }>;
};

export const financeApi = {
  reconcile: (asOf?: string, currency?: string) => {
    const p = new URLSearchParams();
    if (asOf) p.set("asOf", asOf);
    if (currency) p.set("currency", currency);
    return apiFetch<{
      asOf: string;
      currency: string;
      invoiceReceivable: number;
      ledgerReceivable: number;
      receivableDifference: number;
      ledgerCash: number;
      supplierPaymentsPosted: number;
      operatingExpensesPosted: number;
      status: string;
    }>(`/api/finance/reconciliation?${p.toString()}`);
  },
};

export const reportsApi = {
  management: (from?: string, to?: string) => {
    const params = new URLSearchParams();
    if (from) params.set("from", from);
    if (to) params.set("to", to);
    const query = params.toString();
    return apiFetch<ManagementReport>(
      `/api/reports/management${query ? `?${query}` : ""}`,
    );
  },
};

export interface AalBusinessCockpit {
  from: string;
  to: string;
  currency: string;
  shipments: number;
  activeShipments: number;
  deliveredShipments: number;
  grossWeightKg: number;
  chargeableWeightKg: number;
  billed: number;
  collected: number;
  receivable: number;
  totalCost: number;
  grossProfit: number;
  marginPercent: number;
  netIncome: number;
  openTasks: number;
  overdueTasks: number;
  openQuotes: number;
  wonQuotes: number;
  monthly: Array<{
    month: string;
    shipments: number;
    revenue: number;
    collected: number;
    receivable: number;
    cost: number;
    grossProfit: number;
  }>;
  lanes: Array<{
    origin: string;
    destination: string;
    shipments: number;
    revenue: number;
    grossProfit: number;
    delayed: number;
  }>;
}

export interface AalShipmentFinancial {
  shipmentId: string;
  reference: string;
  currency: string | null;
  grossWeightKg: number | null;
  volumetricWeightKg: number | null;
  chargeableWeightKg: number;
  supplierCost: number;
  otherCost: number;
  totalCost: number;
  billed: number;
  collected: number;
  receivable: number;
  supplierPaid: number;
  otherExpenses: number;
  grossProfit: number;
  marginPercent: number;
  netIncome: number;
  paymentStatus: string | null;
}

export const aalBusinessApi = {
  cockpit: (from?: string, to?: string, currency?: string) => {
    const p = new URLSearchParams();
    if (from) p.set("from", from);
    if (to) p.set("to", to);
    if (currency) p.set("currency", currency);
    return apiFetch<AalBusinessCockpit>(
      `/api/aal/business/cockpit?${p.toString()}`,
    );
  },
  shipmentFinancial: (id: string) =>
    apiFetch<AalShipmentFinancial>(
      `/api/aal/business/shipments/${id}/financial`,
    ),
};

export type PlatformFxRate = {
  id: string;
  rateDate: string;
  baseCurrency: string;
  quoteCurrency: string;
  rate: number;
  source: string | null;
};
export type PlatformPeriod = {
  id: string;
  periodStart: string;
  periodEnd: string;
  status: string;
  closedAt: string | null;
  notes: string | null;
};
export type PlatformAdjustment = {
  id: string;
  adjustmentNo: string;
  adjustmentType: string;
  invoiceId: string | null;
  shipmentId: string | null;
  amount: number;
  currency: string;
  reason: string;
  status: string;
};
export type PlatformGeofence = {
  id: string;
  name: string;
  latitude: number;
  longitude: number;
  radiusM: number;
  active: boolean;
};
export type PlatformIntegration = {
  id: string;
  code: string;
  displayName: string;
  protocol: string;
  baseUrl: string | null;
  enabled: boolean;
  healthStatus: string;
  lastSuccessAt: string | null;
  lastFailureAt: string | null;
  lastError: string | null;
};
export const platformApi = {
  setupMfa: (userId: string) =>
    apiFetch<{ secret: string; otpauthUri: string; enabled: boolean }>(
      `/api/platform/mfa/${userId}/setup`,
      { method: "POST" },
    ),
  verifyMfa: (userId: string, code: string) =>
    apiFetch<{
      configured: boolean;
      enabled: boolean;
      verifiedAt: string | null;
    }>(`/api/platform/mfa/${userId}/verify`, {
      method: "POST",
      body: JSON.stringify({ code }),
    }),
  mfaStatus: (userId: string) =>
    apiFetch<{
      configured: boolean;
      enabled: boolean;
      verifiedAt: string | null;
    }>(`/api/platform/mfa/${userId}`),
  fxRates: (from: string, to: string) =>
    apiFetch<PlatformFxRate[]>(`/api/platform/fx-rates?from=${from}&to=${to}`),
  upsertFx: (data: Record<string, unknown>) =>
    apiFetch<PlatformFxRate>("/api/platform/fx-rates", {
      method: "POST",
      body: JSON.stringify(data),
    }),
  periods: () => apiFetch<PlatformPeriod[]>("/api/platform/finance-periods"),
  openPeriod: (data: Record<string, unknown>) =>
    apiFetch<PlatformPeriod>("/api/platform/finance-periods", {
      method: "POST",
      body: JSON.stringify(data),
    }),
  closePeriod: (id: string) =>
    apiFetch<PlatformPeriod>(`/api/platform/finance-periods/${id}/close`, {
      method: "POST",
    }),
  adjustments: () =>
    apiFetch<PlatformAdjustment[]>("/api/platform/adjustments"),
  createAdjustment: (data: Record<string, unknown>) =>
    apiFetch<PlatformAdjustment>("/api/platform/adjustments", {
      method: "POST",
      body: JSON.stringify(data),
    }),
  geofences: () => apiFetch<PlatformGeofence[]>("/api/platform/geofences"),
  createGeofence: (data: Record<string, unknown>) =>
    apiFetch<PlatformGeofence>("/api/platform/geofences", {
      method: "POST",
      body: JSON.stringify(data),
    }),
  evaluateGeofences: (latitude: number, longitude: number) =>
    apiFetch<
      Array<{ distanceM: number; inside: boolean; geofenceName: string }>
    >(
      `/api/platform/geofences/evaluate?latitude=${latitude}&longitude=${longitude}`,
    ),
  integrations: () =>
    apiFetch<PlatformIntegration[]>("/api/platform/integrations"),
  registerIntegration: (data: Record<string, unknown>) =>
    apiFetch<PlatformIntegration>("/api/platform/integrations", {
      method: "POST",
      body: JSON.stringify(data),
    }),
};

export interface CustomerShipment {
  id: string;
  referenceCode: string;
  status: string;
  mode: string;
  origin: string;
  destination: string;
  eta: string | null;
  carrier: string | null;
  trackingToken: string;
}
export interface CustomerQuote {
  id: string;
  quoteId: string;
  quoteDate: string;
  client: string;
  route: string | null;
  serviceType: string | null;
  quotedAmount: number | null;
  currency: string | null;
  validUntil: string | null;
  status: string;
}
export interface CustomerInvoice {
  id: string;
  invoiceNo: string;
  shipmentId: string | null;
  amount: number;
  paid: number;
  outstanding: number;
  currency: string;
  status: string;
  dueDate: string | null;
}
export interface TrackingEvent {
  id: string;
  eventType: string;
  location: string | null;
  notes: string | null;
  occurredAt: string;
}
export interface CustomerDocument {
  id: string;
  shipmentId: string;
  type: string;
  uri: string;
  createdAt: string;
}
export const customerApi = {
  profile: () =>
    apiFetch<{
      userId: string;
      email: string;
      clientId: string;
      company: string;
      contact: string;
      phone: string;
      country: string;
      city: string;
    }>("/api/customer/profile"),
  shipments: () => apiFetch<CustomerShipment[]>("/api/customer/shipments"),
  shipment: (id: string) =>
    apiFetch<CustomerShipment>(`/api/customer/shipments/${id}`),
  events: (id: string) =>
    apiFetch<TrackingEvent[]>(`/api/customer/shipments/${id}/events`),
  documents: (id: string) =>
    apiFetch<CustomerDocument[]>(`/api/customer/shipments/${id}/documents`),
  quotes: () => apiFetch<CustomerQuote[]>("/api/customer/quotes"),
  invoices: () => apiFetch<CustomerInvoice[]>("/api/customer/invoices"),
};

export interface AalSystemSetting {
  group: string;
  key: string;
  value: string | null;
  updatedBy: string | null;
  updatedAt: string | null;
}

export const settingsApi = {
  list: (group?: string) =>
    apiFetch<AalSystemSetting[]>(
      `/api/settings${group ? `?group=${encodeURIComponent(group)}` : ""}`,
    ),
  save: (group: string, key: string, value: string) =>
    apiFetch<AalSystemSetting>("/api/settings", {
      method: "PUT",
      body: JSON.stringify({ group, key, value }),
    }),
  remove: (group: string, key: string) =>
    apiFetch<void>(
      `/api/settings?group=${encodeURIComponent(group)}&key=${encodeURIComponent(key)}`,
      { method: "DELETE" },
    ),
};

export interface AdvancedShipment360 {
  shipment: Record<string, unknown>;
  parties: Record<string, unknown>[];
  cargo: Record<string, unknown>[];
  pieces: Record<string, unknown>[];
  legs: Record<string, unknown>[];
  journeyLegs: Record<string, unknown>[];
  milestones: Record<string, unknown>[];
  legMilestones: Record<string, unknown>[];
  documents: Record<string, unknown>[];
  legDocuments: Record<string, unknown>[];
  costs: Record<string, unknown>[];
  tracking: Record<string, unknown>[];
  invoices: Record<string, unknown>[];
  payments: Record<string, unknown>[];
  exceptions: Record<string, unknown>[];
  customs: Record<string, unknown>[];
  customsWorkflows: Record<string, unknown>[];
  pod: Record<string, unknown>[];
  oceanCharges: Record<string, unknown>[];
  supplierBills: Record<string, unknown>[];
  readiness: Record<string, unknown>[];
  profitability: Record<string, unknown>;
}

export interface AdvancedAnalytics {
  shipments: Record<string, number>;
  receivables: Record<string, number>;
  modes: Array<Record<string, unknown>>;
  customers: Array<Record<string, unknown>>;
  carriers: Array<Record<string, unknown>>;
}

export const advancedLogisticsApi = {
  capabilities: () =>
    apiFetch<Record<string, unknown>>("/api/advanced-logistics/capabilities"),
  shipment360: (id: string) =>
    apiFetch<AdvancedShipment360>(
      `/api/advanced-logistics/shipments/${id}/360`,
    ),
  analytics: () =>
    apiFetch<AdvancedAnalytics>("/api/advanced-logistics/analytics"),
  readiness: (id: string) =>
    apiFetch<Record<string, unknown>>(
      `/api/advanced-logistics/shipments/${id}/readiness`,
      { method: "POST", body: JSON.stringify({ trigger: "MANUAL" }) },
    ),
  evaluateExceptions: (id: string) =>
    apiFetch<Record<string, unknown>>(
      `/api/advanced-logistics/shipments/${id}/exceptions/evaluate`,
      { method: "POST" },
    ),
  ratePreview: (data: Record<string, unknown>) =>
    apiFetch<Record<string, unknown>>(
      "/api/advanced-logistics/rating/preview",
      {
        method: "POST",
        body: JSON.stringify(data),
      },
    ),
  addQuoteCharge: (quoteId: string, data: Record<string, unknown>) =>
    apiFetch<Record<string, unknown>>(
      `/api/advanced-logistics/quotes/${quoteId}/charges`,
      { method: "POST", body: JSON.stringify(data) },
    ),
  oceanCharge: (shipmentId: string, data: Record<string, unknown>) =>
    apiFetch<Record<string, unknown>>(
      `/api/advanced-logistics/ocean/shipments/${shipmentId}/charges`,
      { method: "POST", body: JSON.stringify(data) },
    ),
  warehouseBarcode: (data: Record<string, unknown>) =>
    apiFetch<Record<string, unknown>>(
      "/api/advanced-logistics/warehouse/barcodes",
      {
        method: "POST",
        body: JSON.stringify(data),
      },
    ),
  cycleCount: (data: Record<string, unknown>) =>
    apiFetch<Record<string, unknown>>(
      "/api/advanced-logistics/warehouse/cycle-counts",
      {
        method: "POST",
        body: JSON.stringify(data),
      },
    ),
  customsLine: (data: Record<string, unknown>) =>
    apiFetch<Record<string, unknown>>("/api/advanced-logistics/customs/lines", {
      method: "POST",
      body: JSON.stringify(data),
    }),
  supplierBill: (data: Record<string, unknown>) =>
    apiFetch<Record<string, unknown>>(
      "/api/advanced-logistics/finance/supplier-bills",
      {
        method: "POST",
        body: JSON.stringify(data),
      },
    ),
  bankTransaction: (data: Record<string, unknown>) =>
    apiFetch<Record<string, unknown>>(
      "/api/advanced-logistics/finance/bank-transactions",
      {
        method: "POST",
        body: JSON.stringify(data),
      },
    ),
  carrierPerformance: (data: Record<string, unknown>) =>
    apiFetch<Record<string, unknown>>(
      "/api/advanced-logistics/carriers/performance",
      {
        method: "POST",
        body: JSON.stringify(data),
      },
    ),
  feedback: (data: Record<string, unknown>) =>
    apiFetch<Record<string, unknown>>(
      "/api/advanced-logistics/customer-feedback",
      {
        method: "POST",
        body: JSON.stringify(data),
      },
    ),
  webhook: (data: Record<string, unknown>) =>
    apiFetch<Record<string, unknown>>("/api/advanced-logistics/webhooks", {
      method: "POST",
      body: JSON.stringify(data),
    }),
  mobileSync: (data: Record<string, unknown>) =>
    apiFetch<Record<string, unknown>>("/api/advanced-logistics/mobile/sync", {
      method: "POST",
      body: JSON.stringify(data),
    }),
  workflowRule: (data: Record<string, unknown>) =>
    apiFetch<Record<string, unknown>>(
      "/api/advanced-logistics/workflow-rules",
      {
        method: "POST",
        body: JSON.stringify(data),
      },
    ),
  documentSignature: (data: Record<string, unknown>) =>
    apiFetch<Record<string, unknown>>(
      "/api/advanced-logistics/documents/signatures",
      {
        method: "POST",
        body: JSON.stringify(data),
      },
    ),
  runAutomation: (data: Record<string, unknown>) =>
    apiFetch<Record<string, unknown>>(
      "/api/advanced-logistics/automation/run",
      {
        method: "POST",
        body: JSON.stringify(data),
      },
    ),
  integrations: () =>
    apiFetch<Record<string, unknown>[]>("/api/advanced-logistics/integrations"),
};

export const enterpriseCompletionApi = {
  checklist: () =>
    apiFetch<Record<string, unknown>[]>("/api/enterprise-completion/checklist"),
  carrierContract: (data: Record<string, unknown>) =>
    apiFetch<Record<string, unknown>>(
      "/api/enterprise-completion/carriers/contracts",
      {
        method: "POST",
        body: JSON.stringify(data),
      },
    ),
  carrierSettlement: (data: Record<string, unknown>) =>
    apiFetch<Record<string, unknown>>(
      "/api/enterprise-completion/carriers/settlements",
      {
        method: "POST",
        body: JSON.stringify(data),
      },
    ),
  financeNote: (data: Record<string, unknown>) =>
    apiFetch<Record<string, unknown>>(
      "/api/enterprise-completion/finance/notes",
      {
        method: "POST",
        body: JSON.stringify(data),
      },
    ),
  taxRule: (data: Record<string, unknown>) =>
    apiFetch<Record<string, unknown>>(
      "/api/enterprise-completion/finance/tax-rules",
      {
        method: "POST",
        body: JSON.stringify(data),
      },
    ),
  customerStatement: (client: string) =>
    apiFetch<Record<string, unknown>[]>(
      `/api/enterprise-completion/finance/customer-statements?client=${encodeURIComponent(client)}`,
    ),
  supplierStatement: (supplier: string) =>
    apiFetch<Record<string, unknown>[]>(
      `/api/enterprise-completion/finance/supplier-statements?supplier=${encodeURIComponent(supplier)}`,
    ),
  closePeriod: (id: string) =>
    apiFetch<Record<string, unknown>>(
      `/api/enterprise-completion/finance/periods/${id}/close`,
      { method: "POST" },
    ),
  accountingExport: (data: Record<string, unknown>) =>
    apiFetch<Record<string, unknown>>(
      "/api/enterprise-completion/finance/accounting-export",
      { method: "POST", body: JSON.stringify(data) },
    ),
  forecast: () =>
    apiFetch<Record<string, unknown>>(
      "/api/enterprise-completion/analytics/forecast",
    ),
  integrationHealth: (code: string) =>
    apiFetch<Record<string, unknown>>(
      `/api/enterprise-completion/integrations/${encodeURIComponent(code)}/health`,
    ),
  automationTick: (data: Record<string, unknown>) =>
    apiFetch<Record<string, unknown>>(
      "/api/enterprise-completion/automation/tick",
      { method: "POST", body: JSON.stringify(data) },
    ),
};
