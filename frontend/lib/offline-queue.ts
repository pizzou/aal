export type MobileOperation = {
  deviceId: string;
  operationId: string;
  entityType: string;
  entityId?: string;
  payloadJson: string;
};

const DB_NAME = "aal-mobile-ops";
const STORE = "operations";
const FALLBACK_KEY = "aal-mobile-pending-operations";

function fallbackRead(): MobileOperation[] {
  try {
    const value = JSON.parse(localStorage.getItem(FALLBACK_KEY) || "[]");
    return Array.isArray(value) ? value : [];
  } catch {
    return [];
  }
}

function fallbackWrite(items: MobileOperation[]) {
  localStorage.setItem(FALLBACK_KEY, JSON.stringify(items.slice(-200)));
}

function db(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    if (typeof indexedDB === "undefined") return reject(new Error("IndexedDB unavailable"));
    const request = indexedDB.open(DB_NAME, 1);
    request.onupgradeneeded = () => {
      if (!request.result.objectStoreNames.contains(STORE)) request.result.createObjectStore(STORE, { keyPath: "operationId" });
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error || new Error("Unable to open offline queue"));
  });
}

export async function queueMobileOperation(operation: MobileOperation) {
  try {
    const database = await db();
    await new Promise<void>((resolve, reject) => {
      const tx = database.transaction(STORE, "readwrite");
      tx.objectStore(STORE).put(operation);
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error || new Error("Unable to queue operation"));
    });
    database.close();
  } catch {
    fallbackWrite([...fallbackRead().filter((x) => x.operationId !== operation.operationId), operation]);
  }
}

export async function pendingMobileOperations(): Promise<MobileOperation[]> {
  try {
    const database = await db();
    const items = await new Promise<MobileOperation[]>((resolve, reject) => {
      const tx = database.transaction(STORE, "readonly");
      const req = tx.objectStore(STORE).getAll();
      req.onsuccess = () => resolve((req.result || []) as MobileOperation[]);
      req.onerror = () => reject(req.error || new Error("Unable to read offline queue"));
    });
    database.close();
    return items;
  } catch {
    return fallbackRead();
  }
}

export async function removeMobileOperation(operationId: string) {
  try {
    const database = await db();
    await new Promise<void>((resolve, reject) => {
      const tx = database.transaction(STORE, "readwrite");
      tx.objectStore(STORE).delete(operationId);
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error || new Error("Unable to remove queued operation"));
    });
    database.close();
  } catch {
    fallbackWrite(fallbackRead().filter((x) => x.operationId !== operationId));
  }
}
