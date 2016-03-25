/**
 * Copyright 2016 The OpenTracing Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.opentracing.propagation;

import java.time.Instant;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.Tracer.SpanBuilder;

abstract class AbstractTracer implements Tracer {

    static final boolean BAGGAGE_ENABLED = !Boolean.getBoolean("opentracing.propagation.dropBaggage");

    private final PropagationRegistry registry = new PropagationRegistry();

    protected AbstractTracer() {
        registry.register(Map.class, new TextFormatInjectorImpl());
    }

    @Override
    public SpanBuilder buildSpan(String operationName){
        return new SpanBuilderImpl().withOperationName(operationName);
    }

    @Override
    public <T> void inject(Span span, T carrier) {
        registry.getInjector((Class<T>)carrier.getClass()).inject(span, carrier);
    }

    @Override
    public <T> SpanBuilder join(T carrier) {
        return registry.getExtractor((Class<T>)carrier.getClass()).join(carrier);
    }

    public <T> Injector<T> register(Class<T> carrierType, Injector<T> injector) {
        return registry.register(carrierType, injector);
    }

    public <T> Extractor<T> register(Class<T> carrierType, Extractor<T> extractor) {
        return registry.register(carrierType, extractor);
    }

    private static class PropagationRegistry {

        private final ConcurrentMap<Class, Injector> injectors = new ConcurrentHashMap<>();
        private final ConcurrentMap<Class, Extractor> extractors = new ConcurrentHashMap<>();

        public <T> Injector<T> getInjector(Class<T> carrierType) {
            Class<?> c = carrierType;
            // match first on concrete classes
            do {
                if (injectors.containsKey(c)) {
                    return injectors.get(c);
                }
                c = c.getSuperclass();
            } while (c != null);
            // match second on interfaces
            for (Class<?> iface : carrierType.getInterfaces()) {
                if (injectors.containsKey(c)) {
                    return injectors.get(c);
                }
            }
            throw new AssertionError("no registered injector for " + carrierType.getName());
        }

        public <T> Extractor<T> getExtractor(Class<T> carrierType) {
            Class<?> c = carrierType;
            // match first on concrete classes
            do {
                if (extractors.containsKey(c)) {
                    return extractors.get(c);
                }
                c = c.getSuperclass();
            } while (c != null);
            // match second on interfaces
            for (Class<?> iface : carrierType.getInterfaces()) {
                if (extractors.containsKey(c)) {
                    return extractors.get(c);
                }
            }
            throw new AssertionError("no registered extractor for " + carrierType.getName());
        }

        public <T> Injector<T> register(Class<T> carrierType, Injector<T> injector) {
            return injectors.putIfAbsent(carrierType, injector);
        }

        public <T> Extractor<T> register(Class<T> carrierType, Extractor<T> extractor) {
            return extractors.putIfAbsent(carrierType, extractor);
        }
    }

    static final class SpanBuilderImpl implements SpanBuilder {

        SpanBuilderImpl() {}

        final Map<String,Object> traceState = new HashMap<>();
        final Map<String,String> baggage = new HashMap<>();

        private String operationName = null;
        private Span parent = null;
        private Instant start = Instant.now();
        private final Map<String,String> stringTags = new HashMap<>();
        private final Map<String,Boolean> booleanTags = new HashMap<>();
        private final Map<String,Number> numberTags = new HashMap<>();

        @Override
        public Tracer.SpanBuilder withOperationName(String operationName) {
            this.operationName = operationName;
            return this;
        }

        @Override
        public Tracer.SpanBuilder withParent(Span parent) {
            this.parent = parent;
            return this;
        }

        @Override
        public Tracer.SpanBuilder withTag(String key, String value) {
            stringTags.put(key, value);
            return this;
        }

        @Override
        public Tracer.SpanBuilder withTag(String key, boolean value) {
            booleanTags.put(key, value);
            return this;
        }

        @Override
        public Tracer.SpanBuilder withTag(String key, Number value) {
            numberTags.put(key, value);
            return this;
        }

        @Override
        public Tracer.SpanBuilder withStartTimestamp(long microseconds) {
            long epochSeconds = TimeUnit.MICROSECONDS.toSeconds(microseconds);
            long nanos = TimeUnit.MICROSECONDS.toNanos(microseconds) - TimeUnit.SECONDS.toNanos(epochSeconds);
            this.start = Instant.ofEpochSecond(epochSeconds, nanos);
            return this;
        }

        @Override
        public Span start() {
            SpanImpl span = new SpanImpl(operationName, Optional.ofNullable(parent), start);
            span.traceState.putAll(traceState);
            stringTags.entrySet().stream().forEach((entry) -> { span.setTag(entry.getKey(), entry.getValue()); });
            booleanTags.entrySet().stream().forEach((entry) -> { span.setTag(entry.getKey(), entry.getValue()); });
            numberTags.entrySet().stream().forEach((entry) -> { span.setTag(entry.getKey(), entry.getValue()); });
            baggage.entrySet().stream().forEach((entry) -> { span.setBaggageItem(entry.getKey(), entry.getValue()); });
            return span;
        }
    }

}
