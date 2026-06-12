const SHELL_CACHE_NAME = 'mushaf-qiyam-shell-v4';
const IMAGES_CACHE_NAME = 'mushaf-qiyam-images-v1';
const MODEL_CACHE_NAME = 'mushaf-qiyam-model-v2';

const ASSETS_TO_CACHE = [
  './',
  'index.html',
  'index.css',
  'app.js',
  'whisper-worker.js',
  'transformers.min.js',
  'ort-wasm-simd-threaded.jsep.wasm',
  'manifest.json',
  'version.json',
  'quran-pages.json'
];

// استراتيجية التخزين المؤقت أولاً (Cache-First Strategy)
function cacheFirst(request, cacheName, fallbackResponse = null) {
  return caches.open(cacheName).then((cache) => {
    return cache.match(request).then((cachedResponse) => {
      if (cachedResponse) {
        return cachedResponse;
      }
      return fetch(request).then((networkResponse) => {
        if (networkResponse.status === 200) {
          cache.put(request, networkResponse.clone());
        }
        return networkResponse;
      }).catch((err) => {
        if (fallbackResponse) {
          return fallbackResponse;
        }
        throw err;
      });
    });
  });
}

// Install Event - Pre-cache App Shell
self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(SHELL_CACHE_NAME).then((cache) => {
      console.log('[Service Worker] Caching App Shell');
      return cache.addAll(ASSETS_TO_CACHE);
    }).then(() => self.skipWaiting())
  );
});

// Activate Event - Clean up old caches
self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((cacheNames) => {
      return Promise.all(
        cacheNames.map((cacheName) => {
          if (cacheName !== SHELL_CACHE_NAME && cacheName !== IMAGES_CACHE_NAME && cacheName !== MODEL_CACHE_NAME) {
            console.log('[Service Worker] Deleting old cache:', cacheName);
            return caches.delete(cacheName);
          }
        })
      );
    }).then(() => self.clients.claim())
  );
});

// Fetch Event - Handle Caching Strategies
self.addEventListener('fetch', (event) => {
  const requestUrl = new URL(event.request.url);

  // Check if it's a request for jsdelivr (Transformers.js and ONNX Runtime WASM runtime)
  if (requestUrl.host === 'cdn.jsdelivr.net') {
    event.respondWith(cacheFirst(event.request, SHELL_CACHE_NAME));
  }
  // Check if it's a request for the local model files
  else if (requestUrl.pathname.includes('/models/')) {
    event.respondWith(cacheFirst(event.request, MODEL_CACHE_NAME));
  }
  // Check if it's a request for Quran page images (raw.githubusercontent.com/GovarJabbar/Quran-PNG)
  else if (requestUrl.host === 'raw.githubusercontent.com' && requestUrl.pathname.includes('/Quran-PNG/')) {
    event.respondWith(
      cacheFirst(
        event.request,
        IMAGES_CACHE_NAME,
        new Response('Offline image not found', { status: 404 })
      )
    );
  } else {
    // App Shell Strategy: Stale-While-Revalidate
    event.respondWith(
      caches.match(event.request).then((cachedResponse) => {
        const fetchPromise = fetch(event.request).then((networkResponse) => {
          if (networkResponse.status === 200) {
            caches.open(SHELL_CACHE_NAME).then((cache) => {
              cache.put(event.request, networkResponse.clone());
            });
          }
          return networkResponse;
        }).catch(() => {
          // If offline and not in cache, fallback if necessary
        });

        return cachedResponse || fetchPromise;
      })
    );
  }
});

// Message Event - Handle caching all images at once from the UI
self.addEventListener('message', (event) => {
  if (event.data && event.data.action === 'cache-images') {
    const urlsToCache = event.data.urls;
    const sourceClient = event.source;

    const cachePromise = caches.open(IMAGES_CACHE_NAME).then((cache) => {
      console.log('[Service Worker] Pre-caching Quran Images, count:', urlsToCache.length);

      // Cache files sequentially in small batches to avoid memory issues on tablet
      let promise = Promise.resolve();
      const batchSize = 10;

      for (let i = 0; i < urlsToCache.length; i += batchSize) {
        const batchIndex = i;
        const batch = urlsToCache.slice(i, i + batchSize);
        promise = promise.then(() => {
          // Report progress back to client
          const progress = Math.min(100, Math.round((batchIndex / urlsToCache.length) * 100));
          const sendProgress = (client) => client.postMessage({
            type: 'cache-progress',
            progress: progress,
            cachedCount: batchIndex,
            totalCount: urlsToCache.length
          });

          if (sourceClient) {
            sourceClient.postMessage({
              type: 'cache-progress',
              progress: progress,
              cachedCount: batchIndex,
              totalCount: urlsToCache.length
            });
          }
          self.clients.matchAll().then((clients) => clients.forEach(sendProgress));

          return Promise.all(
            batch.map(url => {
              return cache.match(url).then(exists => {
                if (exists) return Promise.resolve();
                return cache.add(url).catch(err => console.error('Failed to cache image:', url, err));
              });
            })
          );
        });
      }

      return promise.then(() => {
        console.log('[Service Worker] Pre-caching Quran Images completed!');
        const completedMsg = {
          type: 'cache-completed',
          progress: 100,
          cachedCount: urlsToCache.length,
          totalCount: urlsToCache.length
        };
        if (sourceClient) {
          sourceClient.postMessage(completedMsg);
        }
        self.clients.matchAll().then((clients) => clients.forEach(c => c.postMessage(completedMsg)));
      });
    });

    if (event.waitUntil) {
      event.waitUntil(cachePromise);
    }
  }
});

