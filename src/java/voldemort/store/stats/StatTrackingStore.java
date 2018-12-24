/*
 * Copyright 2008-2009 LinkedIn, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package voldemort.store.stats;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.log.Fields;
import io.opentracing.tag.Tags;

import voldemort.VoldemortException;
import voldemort.store.CompositeVoldemortRequest;
import voldemort.store.DelegatingStore;
import voldemort.store.Store;
import voldemort.store.StoreCapabilityType;
import voldemort.tracer.VoldemortTracer;
import voldemort.utils.ByteArray;
import voldemort.versioning.ObsoleteVersionException;
import voldemort.versioning.Version;
import voldemort.versioning.Versioned;

/**
 * A store wrapper that tracks basic usage statistics
 */
public class StatTrackingStore extends DelegatingStore<ByteArray, byte[], byte[]> {

    private final StoreStats stats;

    private StoreStats getOrCreateStoreStats(Map<String, StoreStats> cachedStats,
                                             StoreStats parentStats) {
        if(cachedStats == null) {
            throw new IllegalArgumentException("cachedStats is null");
        }
        synchronized(cachedStats) {
            String storeName = getName();
            if(cachedStats.containsKey(storeName)) {
                return cachedStats.get(storeName);
            }
            StoreStats storeStats = new StoreStats(getName(), parentStats);
            cachedStats.put(storeName, storeStats);
            return storeStats;
        }
    }

    public StatTrackingStore(Store<ByteArray, byte[], byte[]> innerStore,
                             StoreStats parentStats,
                             Map<String, StoreStats> cachedStats) {
        super(innerStore);
        this.stats = getOrCreateStoreStats(cachedStats, parentStats);
    }

    public StatTrackingStore(Store<ByteArray, byte[], byte[]> innerStore, StoreStats parentStats) {
        super(innerStore);
        this.stats = new StoreStats(getName(), parentStats);
    }

    @Override
    public boolean delete(ByteArray key, Version version) throws VoldemortException {
        long start = System.nanoTime();
        Span span = VoldemortTracer.buildSpan(getClass().getSimpleName() + "-DELETE").start();
        span.setTag("op", "DELETE");
        span.setTag("store", super.getName());
        span.setTag("key", key.toString());
        try (Scope scope = VoldemortTracer.scopeManager().activate(span, false)) {
            return super.delete(key, version);
        } catch(VoldemortException e) {
            Tags.ERROR.set(span, true);
            span.log(buildHashMap(e));
            stats.recordTime(Tracked.EXCEPTION, System.nanoTime() - start);
            throw e;
        } finally {
            stats.recordDeleteTime(System.nanoTime() - start, key.get().length);
            span.finish();
        }
    }

    @Override
    public List<Version> getVersions(ByteArray key) throws VoldemortException {
        List<Version> result = null;
        long start = System.nanoTime();
        try {
            result = super.getVersions(key);
            return result;
        } catch(VoldemortException e) {
            stats.recordTime(Tracked.EXCEPTION, System.nanoTime() - start);
            throw e;
        } finally {
            long duration = System.nanoTime() - start;
            boolean returningEmpty = true;
            if(result != null) {
                returningEmpty = result.size() == 0;
            }
            stats.recordGetVersionsTime(duration, returningEmpty);
        }
    }

    @Override
    public List<Versioned<byte[]>> get(ByteArray key, byte[] transforms) throws VoldemortException {
        List<Versioned<byte[]>> result = null;
        long start = System.nanoTime();
        Span span = VoldemortTracer.buildSpan(getClass().getSimpleName() + "-GET").start();
        span.setTag("op", "GET");
        span.setTag("store", super.getName());
        span.setTag("key", key.toString());

        try (Scope scope = VoldemortTracer.scopeManager().activate(span, false)) {
            result = super.get(key, transforms);
            return result;
        } catch(VoldemortException e) {
            Tags.ERROR.set(span, true);
            span.log(buildHashMap(e));
            stats.recordTime(Tracked.EXCEPTION, System.nanoTime() - start);
            throw e;
        } finally {
            long duration = System.nanoTime() - start;
            long totalValueBytes = 0;
            boolean returningEmpty = true;
            if(result != null) {
                returningEmpty = result.size() == 0;
                for(Versioned<byte[]> bytes: result) {
                    totalValueBytes += bytes.getValue().length;
                }
            }
            stats.recordGetTime(duration, returningEmpty, totalValueBytes, key.get().length);
            span.finish();
        }
    }

    @Override
    public Map<ByteArray, List<Versioned<byte[]>>> getAll(Iterable<ByteArray> keys,
                                                          Map<ByteArray, byte[]> transforms)
            throws VoldemortException {
        Map<ByteArray, List<Versioned<byte[]>>> result = null;
        long start = System.nanoTime();
        Span span = VoldemortTracer.buildSpan(getClass().getSimpleName() + "-GETALL").start();
        span.setTag("op", "GETALL");
        span.setTag("store", super.getName());
        try (Scope scope = VoldemortTracer.scopeManager().activate(span, false)) {
            result = super.getAll(keys, transforms);
            return result;
        } catch(VoldemortException e) {
            Tags.ERROR.set(span, true);
            span.log(buildHashMap(e));
            stats.recordTime(Tracked.EXCEPTION, System.nanoTime() - start);
            throw e;
        } finally {
            long duration = System.nanoTime() - start;
            long totalValueBytes = 0;
            long totalKeyBytes = 0;
            int requestedValues = 0;
            int returnedValues = 0;

            // Determine how many values were requested
            for(ByteArray k: keys) {
                totalKeyBytes += k.get().length;
                requestedValues++;
            }

            if(result != null) {
                // Determine the number of values being returned
                returnedValues = result.keySet().size();
                // Determine the total size of the response
                for(List<Versioned<byte[]>> value: result.values()) {
                    for(Versioned<byte[]> bytes: value) {
                        totalValueBytes += bytes.getValue().length;
                    }
                }
            }

            stats.recordGetAllTime(duration,
                                   requestedValues,
                                   returnedValues,
                                   totalValueBytes,
                                   totalKeyBytes);

            span.finish();
        }
    }

    @Override
    public void put(ByteArray key, Versioned<byte[]> value, byte[] transforms)
            throws VoldemortException {
        long start = System.nanoTime();
        Span span = VoldemortTracer.buildSpan(getClass().getSimpleName() + "-PUT").start();
        span.setTag("op", "PUT");
        span.setTag("store", super.getName());
        span.setTag("key", key.toString());
        try (Scope scope = VoldemortTracer.scopeManager().activate(span, false)) {
            super.put(key, value, transforms);
        } catch(ObsoleteVersionException e) {
            Tags.ERROR.set(span, true);
            span.log(buildHashMap(e));
            stats.recordTime(Tracked.OBSOLETE, System.nanoTime() - start);
            throw e;
        } catch(VoldemortException e) {
            Tags.ERROR.set(span, true);
            span.log(buildHashMap(e));
            stats.recordTime(Tracked.EXCEPTION, System.nanoTime() - start);
            throw e;
        } finally {
            stats.recordPutTimeAndSize(System.nanoTime() - start,
                                       value.getValue().length,
                                       key.get().length);
            span.finish();
        }
    }

    private Map<String, Object> buildHashMap(Exception e) {
        Map<String, Object> map = new HashMap<>();
        map.put(Fields.EVENT, "error");
        map.put(Fields.ERROR_OBJECT, e);
        map.put(Fields.MESSAGE, e.getMessage());
        return map;
    }

    @Override
    public Object getCapability(StoreCapabilityType capability) {
        if(StoreCapabilityType.STAT_TRACKER.equals(capability)) {
            return this.stats;
        } else {
            return super.getCapability(capability);
        }
    }

    public StoreStats getStats() {
        return stats;
    }

    @Override
    public List<Versioned<byte[]>> get(CompositeVoldemortRequest<ByteArray, byte[]> request)
            throws VoldemortException {
        List<Versioned<byte[]>> result = null;
        long start = System.nanoTime();
        Span span = VoldemortTracer.buildSpan(getClass().getSimpleName() + "-GET-COMPOSITE-REQ")
                                   .start();
        span.setTag("op", "GET-COMPOSITE-REQ");
        span.setTag("store", super.getName());
        try (Scope scope = VoldemortTracer.scopeManager().activate(span, false)) {
            result = super.get(request);
            return result;
        } catch(VoldemortException e) {
            Tags.ERROR.set(span, true);
            span.log(buildHashMap(e));
            stats.recordTime(Tracked.EXCEPTION, System.nanoTime() - start);
            throw e;
        } finally {
            long duration = System.nanoTime() - start;
            long totalValueBytes = 0;
            boolean returningEmpty = true;
            if(result != null) {
                returningEmpty = result.size() == 0;
                for(Versioned<byte[]> bytes: result) {
                    totalValueBytes += bytes.getValue().length;
                }
            }
            stats.recordGetTime(duration,
                                returningEmpty,
                                totalValueBytes,
                                request.getKey().get().length);
            span.finish();
        }
    }

    @Override
    // TODO: Validate all the keys in the request object
    public Map<ByteArray, List<Versioned<byte[]>>> getAll(CompositeVoldemortRequest<ByteArray, byte[]> request)
            throws VoldemortException {
        Map<ByteArray, List<Versioned<byte[]>>> result = null;
        long start = System.nanoTime();
        try {
            result = super.getAll(request);
            return result;
        } catch(VoldemortException e) {
            stats.recordTime(Tracked.EXCEPTION, System.nanoTime() - start);
            throw e;
        } finally {
            long duration = System.nanoTime() - start;
            long totalValueBytes = 0;
            long totalKeyBytes = 0;
            int requestedValues = 0;
            int returnedValues = 0;

            // Determine how many values were requested
            for(ByteArray k: request.getIterableKeys()) {
                totalKeyBytes += k.get().length;
                requestedValues++;
            }

            if(result != null) {
                // Determine the number of values being returned
                returnedValues = result.keySet().size();
                // Determine the total size of the response
                for(List<Versioned<byte[]>> value: result.values()) {
                    for(Versioned<byte[]> bytes: value) {
                        totalValueBytes += bytes.getValue().length;
                    }
                }
            }

            stats.recordGetAllTime(duration,
                                   requestedValues,
                                   returnedValues,
                                   totalValueBytes,
                                   totalKeyBytes);
        }
    }

    @Override
    public void put(CompositeVoldemortRequest<ByteArray, byte[]> request)
            throws VoldemortException {
        long start = System.nanoTime();
        try {
            super.put(request);
        } catch(ObsoleteVersionException e) {
            stats.recordTime(Tracked.OBSOLETE, System.nanoTime() - start);
            throw e;
        } catch(VoldemortException e) {
            stats.recordTime(Tracked.EXCEPTION, System.nanoTime() - start);
            throw e;
        } finally {
            stats.recordPutTimeAndSize(System.nanoTime() - start,
                                       request.getValue().getValue().length,
                                       request.getKey().get().length);
        }

    }

    @Override
    public boolean delete(CompositeVoldemortRequest<ByteArray, byte[]> request)
            throws VoldemortException {
        long start = System.nanoTime();
        try {
            return super.delete(request);
        } catch(VoldemortException e) {
            stats.recordTime(Tracked.EXCEPTION, System.nanoTime() - start);
            throw e;
        } finally {
            stats.recordDeleteTime(System.nanoTime() - start, request.getKey().get().length);
        }
    }
}
