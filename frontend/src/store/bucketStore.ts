import { create } from "zustand";

export type UUID = string; // You can enhance this with specific UUID validation if needed
export interface SchemaDetails {
  schemaId: string;
  schema: any;
  description: string;
}
export interface CollectionDetails {
  collectionName: string;
  scopeName: string;
  bucketName: string;
  items: number; // kv_collection_item_count
  diskSize: number; // kv_collection_data_size_bytes
  memUsed: number; // kv_collection_mem_used_bytes
  ops: number; // kv_collection_ops
  indexes: number; // index_count
  maxTTL?: number; // TTL
  schemas?: SchemaDetails[]; //support for multiple schemas per collection
  schemaDescription?: string;
  sampleDoc?: any;
}
export interface ScopeDetails {
  scopeName: string;
  bucketName: string;
  read: boolean;
  write: boolean;
}
export interface BucketDetails {
  bucketName: string;
  bucketType: string;
  storageBackend: string;
  evictionPolicy: string;
  itemCount: number;
  opsPerSec: number;
  replicaNumber: number;
  ram: number;
  diskUsed: number;
  durabilityMinLevel: string;
  conflictResolutionType: string;
  maxTTL: number;
  quotaPercentUsed: number;
  residentRatio: number;
  cacheHit: number;
  // Collection metrics from backend
  collectionMetrics?: { [key: string]: { [key: string]: any } };
  // Note: No need for isFhirBucket flag since ALL buckets in store are FHIR-enabled
}
export interface IndexDetails {
  bucket: string;
  scope: string;
  collection: string;
  instId: number;
  indexName: string;
  index: string;
  definition: string;
  status: string;
  hosts: string[];
  numPartition: number;
  numReplica: number;
  replicaId: number;
  lastScanTime: string;
  progress: number;
  predicate: string;
  filterStr: string;
}
export interface IndexPerformance {
  reqRate: number;
  resident: number;
  items: number;
  dataSize: number;
  diskSize: number;
  scanLatency: number;
  cacheMissRatio: number;
  mutationsRemaining: number;
  fragmentation: number;
}

// Fixed scopes for FHIR server
export const FIXED_SCOPES: ScopeDetails[] = [
  {
    scopeName: "Admin",
    bucketName: "",
    read: true,
    write: true,
  },
  {
    scopeName: "Resources",
    bucketName: "",
    read: true,
    write: true,
  },
];

export type BucketStore = {
  buckets: { [connectionId: string]: BucketDetails[] };
  setBuckets: (connectionId: string, buckets: BucketDetails[]) => void;

  // Get all buckets (since all buckets in store are FHIR-enabled)
  getFhirBuckets: (connectionId: string) => BucketDetails[];

  // Check if a bucket is a FHIR bucket (always true for buckets in our store)
  isFhirBucket: (bucketName: string) => boolean;

  activeBucket: { [connectionId: string]: BucketDetails | null };
  setActiveBucket: (connectionId: string, bucketName: string | null) => void;
  getActiveBucket: (connectionId: string) => BucketDetails | null;

  activeScope: { [connectionId: string]: string | null };
  setActiveScope: (connectionId: string, scopeName: string | null) => void;
  getActiveScope: (connectionId: string) => string | null;

  scopes: { [connectionId: string]: ScopeDetails[] };
  setScopes: (connectionId: string, value: ScopeDetails[]) => void;

  collections: { [connectionId: string]: CollectionDetails[] };
  setCollections: (connectionId: string, value: CollectionDetails[]) => void;

  indexDetails: { [connectionId: string]: IndexDetails[] };
  setIndexDetails: (connectionId: string, value: IndexDetails[]) => void;
  getIndexDetails: (connectionId: string) => IndexDetails[] | undefined;

  status: { [connectionId: string]: string };
  setStatus: (connectionId: string, value: string) => void;
  getStatus: (connectionId: string) => string | undefined;

  clearBucketData: (connectionId: string) => void;

  // Fetch bucket data from backend
  fetchBucketData: (connectionId: string) => Promise<BucketDetails[]>;

  // Helper functions for session storage
  _saveActiveState: (connectionId: string) => void;
  _loadActiveState: (connectionId: string) => any;
};

export const useBucketStore = create<BucketStore>()((set, get) => ({
  buckets: {},
  activeBucket: {},
  activeScope: {},
  scopes: {},
  collections: {},
  indexDetails: {},
  status: {},

  // Helper function to save active state to session storage
  _saveActiveState: (connectionId: string) => {
    const state = get();
    const activeData = {
      activeBucket: state.activeBucket[connectionId],
      activeScope: state.activeScope[connectionId],
    };
    sessionStorage.setItem(
      `bucketStore_${connectionId}`,
      JSON.stringify(activeData)
    );
  },

  // Helper function to load active state from session storage
  _loadActiveState: (connectionId: string) => {
    try {
      const stored = sessionStorage.getItem(`bucketStore_${connectionId}`);
      return stored ? JSON.parse(stored) : null;
    } catch {
      return null;
    }
  },

  // Setter method for buckets
  setBuckets: (connectionId, buckets) => {
    // console.log(`🪣 setBuckets called for connection: ${connectionId}`);
    // console.log(`🪣 Setting ${buckets.length} buckets:`, buckets);

    set((state) => {
      // console.log(`🪣 Previous state for ${connectionId}:`, {
      //   buckets: state.buckets[connectionId],
      //   activeBucket: state.activeBucket[connectionId],
      //   activeScope: state.activeScope[connectionId],
      // });

      const newState = {
        ...state,
        buckets: {
          ...state.buckets,
          [connectionId]: [...buckets],
        },
        scopes: {
          ...state.scopes,
          [connectionId]: FIXED_SCOPES.map((scope) => ({
            ...scope,
            bucketName: buckets.length > 0 ? buckets[0].bucketName : "",
          })),
        },
      };

      // Get FHIR buckets from the buckets we just set (all buckets are FHIR)
      const fhirBuckets = buckets; // Since backend only returns FHIR buckets
      // console.log(`🪣 FHIR buckets count: ${fhirBuckets.length}`);

      // Auto-set active bucket if only one FHIR bucket exists and no active bucket is set
      if (fhirBuckets.length === 1 && !state.activeBucket[connectionId]) {
        // console.log(
        //   `🪣 Auto-setting active bucket: ${fhirBuckets[0].bucketName}`
        // );
        newState.activeBucket = {
          ...state.activeBucket,
          [connectionId]: fhirBuckets[0],
        };
        // Auto-set first scope as active
        // console.log(
        //   `🪣 Auto-setting active scope: ${FIXED_SCOPES[0].scopeName}`
        // );
        newState.activeScope = {
          ...state.activeScope,
          [connectionId]: FIXED_SCOPES[0].scopeName,
        };
      } else {
        // console.log(
        //   `🪣 Not auto-setting bucket (count: ${
        //     fhirBuckets.length
        //   }, existing active: ${!!state.activeBucket[connectionId]})`
        // );
        // Try to restore from session storage
        const stored = get()._loadActiveState(connectionId);
        if (stored) {
          if (stored.activeBucket) {
            // Only restore if it's a FHIR bucket
            const foundBucket = fhirBuckets.find(
              (b) => b.bucketName === stored.activeBucket.bucketName
            );
            if (foundBucket) {
              newState.activeBucket = {
                ...state.activeBucket,
                [connectionId]: foundBucket,
              };
            }
          }
          if (stored.activeScope) {
            newState.activeScope = {
              ...state.activeScope,
              [connectionId]: stored.activeScope,
            };
          }
        }
      }

      // console.log(`🪣 Final new state for ${connectionId}:`, {
      //   buckets: newState.buckets[connectionId],
      //   activeBucket: newState.activeBucket[connectionId],
      //   activeScope: newState.activeScope[connectionId],
      // });

      return newState;
    });
  }, // Get all buckets (since all buckets in store are FHIR-enabled)
  getFhirBuckets: (connectionId) => {
    // Simply return all buckets since backend only sends FHIR buckets
    const buckets = get().buckets[connectionId] || [];
    // console.log(
    //   `🔍 getFhirBuckets for ${connectionId}: returning ${buckets.length} buckets`,
    //   buckets
    // );
    return buckets;
  },

  // Check if a bucket is a FHIR bucket (always true for buckets in our store)
  isFhirBucket: (bucketName) => {
    // Check if bucket exists in our store - if it does, it's FHIR-enabled
    const state = get();
    for (const connectionId of Object.keys(state.buckets)) {
      const bucket = state.buckets[connectionId]?.find(
        (b) => b.bucketName === bucketName
      );
      if (bucket) {
        return true; // All buckets in store are FHIR-enabled
      }
    }
    return false; // Bucket not in our store, so not FHIR-enabled
  },

  // Set active bucket
  setActiveBucket: (connectionId, bucketName) => {
    set((state) => {
      if (!bucketName) {
        // Clear active bucket
        return {
          ...state,
          activeBucket: {
            ...state.activeBucket,
            [connectionId]: null,
          },
        };
      }

      // Get FHIR buckets only
      const fhirBuckets = get().getFhirBuckets(connectionId);
      const bucket =
        fhirBuckets.find((b) => b.bucketName === bucketName) || null;

      // Only set if it's a FHIR bucket
      if (bucket) {
        return {
          ...state,
          activeBucket: {
            ...state.activeBucket,
            [connectionId]: bucket,
          },
        };
      }

      // If not a FHIR bucket, don't change the state
      return state;
    });
    // Save to session storage
    setTimeout(() => get()._saveActiveState(connectionId), 0);
  },

  // Get active bucket
  getActiveBucket: (connectionId) => get().activeBucket[connectionId] || null,

  // Set active scope
  setActiveScope: (connectionId, scopeName) => {
    set((state) => ({
      ...state,
      activeScope: {
        ...state.activeScope,
        [connectionId]: scopeName,
      },
    }));
    // Save to session storage
    setTimeout(() => get()._saveActiveState(connectionId), 0);
  },

  // Get active scope
  getActiveScope: (connectionId) => get().activeScope[connectionId] || null,

  // Set scopes (though they're fixed, this maintains consistency)
  setScopes: (connectionId, scopes) => {
    set((state) => ({
      ...state,
      scopes: {
        ...state.scopes,
        [connectionId]: [...scopes],
      },
    }));
  },

  // Set collections
  setCollections: (connectionId, collections) => {
    set((state) => ({
      ...state,
      collections: {
        ...state.collections,
        [connectionId]: [...collections],
      },
    }));
  },

  // Set index details
  setIndexDetails: (connectionId, value) =>
    set((state) => ({
      indexDetails: { ...state.indexDetails, [connectionId]: value },
    })),

  // Get index details
  getIndexDetails: (connectionId) => get().indexDetails[connectionId],

  // Set status
  setStatus: (connectionId, value) => {
    set((state) => ({
      status: {
        ...state.status,
        [connectionId]: value,
      },
    }));
  },

  // Get status
  getStatus: (connectionId) => get().status[connectionId],

  // Clear bucket data for a connection
  clearBucketData: (connectionId) => {
    set((state) => ({
      ...state,
      buckets: { ...state.buckets, [connectionId]: [] },
      activeBucket: { ...state.activeBucket, [connectionId]: null },
      activeScope: { ...state.activeScope, [connectionId]: null },
      scopes: { ...state.scopes, [connectionId]: [] },
      collections: { ...state.collections, [connectionId]: [] },
      indexDetails: { ...state.indexDetails, [connectionId]: [] },
      status: { ...state.status, [connectionId]: "" },
    }));
    // Clear session storage
    sessionStorage.removeItem(`bucketStore_${connectionId}`);
  },

  // Fetch FHIR bucket data from backend
  fetchBucketData: async (connectionId: string) => {
    try {
      // console.log(`Fetching FHIR bucket data for connection: ${connectionId}`);

      // Call the backend API to get FHIR bucket details
      const response = await fetch(
        `/api/buckets/fhir/details?connectionName=${encodeURIComponent(
          connectionId
        )}`,
        {
          method: "GET",
          headers: {
            "Content-Type": "application/json",
          },
        }
      );

      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`);
      }

      const bucketData: BucketDetails[] = await response.json();
      // console.log(`📥 Received ${bucketData.length} FHIR buckets:`, bucketData);

      // Update store with fetched data (all FHIR buckets)
      get().setBuckets(connectionId, bucketData);

      // Extract and populate collections from bucket data
      const allCollections: CollectionDetails[] = [];
      bucketData.forEach((bucket) => {
        if (bucket.collectionMetrics) {
          // console.log(
          //   `🔍 Processing collectionMetrics for bucket: ${bucket.bucketName}`,
          //   bucket.collectionMetrics
          // );

          // Convert collection metrics to CollectionDetails format
          Object.entries(bucket.collectionMetrics).forEach(
            ([scopeName, scopeData]) => {
              if (
                scopeData &&
                typeof scopeData === "object" &&
                "collections" in scopeData
              ) {
                const collections = scopeData.collections as {
                  [key: string]: any;
                };
                Object.entries(collections).forEach(
                  ([collectionName, metrics]) => {
                    if (metrics && typeof metrics === "object") {
                      const collectionDetail: CollectionDetails = {
                        collectionName,
                        scopeName,
                        bucketName: bucket.bucketName,
                        items: Number(metrics["items"]) || 0,
                        diskSize: Number(metrics["diskSize"]) || 0,
                        memUsed: Number(metrics["memUsed"]) || 0,
                        ops: Number(metrics["ops"]) || 0,
                        indexes: Number(metrics["indexes"]) || 0, // This might not be in the data yet
                        maxTTL: Number(metrics["maxTTL"]) || 0,
                      };
                      allCollections.push(collectionDetail);
                      // console.log(
                      //   `📦 Added collection: ${scopeName}.${collectionName}`,
                      //   collectionDetail
                      // );
                    }
                  }
                );
              }
            }
          );
        }
      });

      // console.log(
      //   `📦 Extracted ${allCollections.length} collections:`,
      //   allCollections
      // );

      // Update collections in store
      get().setCollections(connectionId, allCollections);

      // console.log(
      //   `✅ Bucket data and collections fetch completed for ${connectionId}`
      // );
      return bucketData;
    } catch (error) {
      console.error("Failed to fetch FHIR bucket data:", error);

      // For development: if backend is not available, return empty array
      // In production, you might want to throw the error
      const bucketData: BucketDetails[] = [];
      get().setBuckets(connectionId, bucketData);
      get().setCollections(connectionId, []);

      return bucketData;
    }
  },
}));

export default useBucketStore;
