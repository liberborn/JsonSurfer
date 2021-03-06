/*
 * The MIT License
 *
 * Copyright (c) 2015 WANG Lingsong
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jsfr.json;

import org.jsfr.json.path.ArrayIndex;
import org.jsfr.json.path.ChildNode;
import org.jsfr.json.path.JsonPath;
import org.jsfr.json.path.PathOperator;
import org.jsfr.json.path.PathOperator.Type;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

import static org.jsfr.json.compiler.JsonPathCompiler.compile;

/**
 * SurfingContext is not thread-safe
 */
public class SurfingContext implements ParsingContext, JsonSaxHandler {

    public static Builder builder() {
        Builder builder = new Builder();
        builder.context = new SurfingContext();
        return builder;
    }

    public static class Builder {

        private SurfingContext context;
        private Map<Integer, ArrayList<Binding>> definiteBindings = new HashMap<Integer, ArrayList<Binding>>();
        private ArrayList<IndefinitePathBinding> indefiniteBindings = new ArrayList<IndefinitePathBinding>();


        public SurfingContext build() {
            if (!context.built) {
                if (!indefiniteBindings.isEmpty()) {
                    Collections.sort(indefiniteBindings);
                    context.indefinitePathLookup = indefiniteBindings.toArray(new IndefinitePathBinding[indefiniteBindings.size()]);
                }
                if (!definiteBindings.isEmpty()) {
                    context.definitePathLookup = new Binding[context.maxDepth - context.minDepth + 1][];
                    for (Map.Entry<Integer, ArrayList<Binding>> entry : definiteBindings.entrySet()) {
                        context.definitePathLookup[entry.getKey() - context.minDepth] = entry.getValue().toArray(new Binding[entry.getValue().size()]);
                    }
                }
                context.built = true;
            }
            return context;
        }

        public Builder bind(String path, JsonPathListener... jsonPathListeners) {
            return bind(compile(path), jsonPathListeners);
        }

        public Builder bind(JsonPath.Builder builder, JsonPathListener... jsonPathListeners) {
            return bind(builder.build(), jsonPathListeners);
        }

        private void check() {
            if (context.built) {
                throw new IllegalStateException("The JsonSurfer is already built");
            }
        }

        public <T> Builder bind(String jsonPath, final Class<T> tClass, TypedJsonPathListener<T>... typedListeners) {
            bind(compile(jsonPath), tClass, typedListeners);
            return this;
        }

        public <T> Builder bind(JsonPath jsonPath, final Class<T> tClass, TypedJsonPathListener<T>... typedListeners) {
            JsonPathListener[] listeners = new JsonPathListener[typedListeners.length];
            int i = 0;
            for (final TypedJsonPathListener<T> typedListener : typedListeners) {
                listeners[i++] = new JsonPathListener() {
                    @Override
                    public void onValue(Object value, ParsingContext parsingContext) throws Exception {
                        ;
                        typedListener.onTypedValue((T) context.getJsonProvider().cast(value, tClass), parsingContext);
                    }
                };
            }
            bind(jsonPath, listeners);
            return this;
        }

        public Builder bind(JsonPath jsonPath, JsonPathListener... jsonPathListeners) {
            check();
            if (!jsonPath.isDefinite()) {
                int minimumDepth = jsonPath.minimumPathDepth();
                indefiniteBindings.add(new IndefinitePathBinding(jsonPath, jsonPathListeners, minimumDepth));
            } else {
                int depth = jsonPath.pathDepth();
                if (depth > context.maxDepth) {
                    context.maxDepth = depth;
                }
                if (depth < context.minDepth) {
                    context.minDepth = depth;
                }
                ArrayList<Binding> bindings = definiteBindings.get(depth);
                if (bindings == null) {
                    bindings = new ArrayList<Binding>();
                    definiteBindings.put(depth, bindings);
                }
                bindings.add(new Binding(jsonPath, jsonPathListeners));
            }
            return this;
        }

        public Builder skipOverlappedPath() {
            check();
            context.skipOverlappedPath = true;
            return this;
        }

        public Builder withJsonProvider(JsonProvider provider) {
            check();
            context.jsonProvider = provider;
            return this;
        }

        public Builder withErrorStrategy(ErrorHandlingStrategy errorHandlingStrategy) {
            check();
            context.errorHandlingStrategy = errorHandlingStrategy;
            return this;
        }

    }

    private static class IndefinitePathBinding extends Binding implements Comparable<IndefinitePathBinding> {

        private int minimumPathDepth;

        public IndefinitePathBinding(JsonPath jsonPath, JsonPathListener[] listeners, int minimumPathDepth) {
            super(jsonPath, listeners);
            this.minimumPathDepth = minimumPathDepth;
        }

        @Override
        public int compareTo(IndefinitePathBinding o) {
            if (minimumPathDepth < o.minimumPathDepth) {
                return -1;
            } else if (minimumPathDepth > o.minimumPathDepth) {
                return 1;
            } else {
                return 0;
            }
        }

    }

    private static class Binding {

        public Binding(JsonPath jsonPath, JsonPathListener[] listeners) {
            this.jsonPath = jsonPath;
            this.listeners = listeners;
        }

        protected JsonPath jsonPath;
        protected JsonPathListener[] listeners;

    }

    private boolean built = false;
    private boolean stopped = false;
    private boolean skipOverlappedPath = false;
    private JsonProvider jsonProvider;
    private ErrorHandlingStrategy errorHandlingStrategy;
    private JsonPosition currentPosition;
    private int minDepth = Integer.MAX_VALUE;
    private int maxDepth = -1;
    private Binding[][] definitePathLookup;
    // sorted by minimum path depth
    private IndefinitePathBinding[] indefinitePathLookup;

    private ContentDispatcher dispatcher = new ContentDispatcher();

    @Override
    public boolean startJSON() {
        if (stopped) {
            return true;
        }
        currentPosition = JsonPosition.start();
        doMatching(false, false, null);
        dispatcher.startJSON();
        return true;
    }

    @Override
    public boolean endJSON() {
        if (stopped) {
            return true;
        }
        dispatcher.endJSON();
        // clear resources
        currentPosition.clear();
        currentPosition = null;
        indefinitePathLookup = null;
        definitePathLookup = null;
        return true;
    }

    @Override
    public boolean startObject() {
        if (stopped) {
            return false;
        }
        PathOperator top = currentPosition.peek();
        if (top.getType() == Type.ARRAY) {
            ((ArrayIndex) top).increaseArrayIndex();
            doMatching(true, false, null);
        }
        dispatcher.startObject();
        return true;
    }

    private void doMatching(boolean initializeCollector, boolean onPrimitive, Object primitive) {
        // skip matching if "skipOverlappedPath" is enable
        if (skipOverlappedPath && !this.dispatcher.isEmpty()) {
            return;
        }
        LinkedList<JsonPathListener> listeners = null;

        int currentDepth = currentPosition.pathDepth();
        if (indefinitePathLookup != null) {
            for (IndefinitePathBinding binding : indefinitePathLookup) {
                if (binding.minimumPathDepth <= currentDepth) {
                    if (binding.jsonPath.match(currentPosition)) {
                        if (onPrimitive) {
                            for (JsonPathListener listener : binding.listeners) {
                                if (isStopped()) {
                                    break;
                                }
                                try {
                                    listener.onValue(primitive, this);
                                } catch (Exception e) {
                                    errorHandlingStrategy.handleExceptionFromListener(e, this);
                                }
                            }
                        } else {
                            if (listeners == null) {
                                listeners = new LinkedList<JsonPathListener>();
                            }
                            Collections.addAll(listeners, binding.listeners);
                        }
                    }
                } else {
                    break;
                }
            }
        }
        if (definitePathLookup != null && !(currentDepth < minDepth || currentDepth > maxDepth)) {
            Binding[] bindings = definitePathLookup[currentDepth - minDepth];
            if (bindings != null) {
                for (Binding binding : bindings) {
                    if (binding.jsonPath.match(currentPosition)) {
                        if (onPrimitive) {
                            for (JsonPathListener listener : binding.listeners) {
                                if (isStopped()) {
                                    break;
                                }
                                try {
                                    listener.onValue(primitive, this);
                                } catch (Exception e) {
                                    errorHandlingStrategy.handleExceptionFromListener(e, this);
                                }
                            }
                        } else {
                            if (listeners == null) {
                                listeners = new LinkedList<JsonPathListener>();
                            }
                            Collections.addAll(listeners, binding.listeners);
                        }
                    }
                }
            }
        }

        if (listeners != null) {
            JsonCollector collector = new JsonCollector(listeners, this, errorHandlingStrategy);
            collector.setProvider(jsonProvider);
            if (initializeCollector) {
                collector.startJSON();
            }
            dispatcher.addReceiver(collector);
        }
    }

    @Override
    public boolean endObject() {
        if (stopped) {
            return false;
        }
        if (currentPosition.peekType() == Type.OBJECT) {
            currentPosition.stepOut();
        }
        dispatcher.endObject();
        return true;
    }

    @Override
    public boolean startObjectEntry(String key) {
        if (stopped) {
            return false;
        }
        currentPosition.stepIntoChild(key);
        dispatcher.startObjectEntry(key);
        doMatching(true, false, null);
        return true;
    }


    @Override
    public boolean startArray() {
        if (stopped) {
            return false;
        }
        PathOperator top = currentPosition.peek();
        if (top.getType() == Type.ARRAY) {
            ((ArrayIndex) top).increaseArrayIndex();
            doMatching(true, false, null);
        }
        currentPosition.stepIntoArray();
        dispatcher.startArray();
        return true;
    }

    @Override
    public boolean endArray() {
        if (stopped) {
            return false;
        }
        currentPosition.stepOut();
        if (currentPosition.peekType() == Type.OBJECT) {
            currentPosition.stepOut();
        }
        dispatcher.endArray();
        return true;
    }

    @Override
    public boolean primitive(final Object value) {
        if (stopped) {
            return false;
        }
        PathOperator top = currentPosition.peek();
        if (top.getType() == Type.ARRAY) {
            ((ArrayIndex) top).increaseArrayIndex();
            doMatching(true, true, value);
        } else if (top.getType() == Type.OBJECT) {
            currentPosition.stepOut();
        }
        dispatcher.primitive(value);
        return true;
    }

    @Override
    public String getJsonPath() {
        return this.currentPosition.toString();
    }

    @Override
    public String getKey() {
        PathOperator peek = currentPosition.peek();
        if (peek.getType() == Type.OBJECT) {
            return ((ChildNode) peek).getKey();
        }
        return null;
    }

    @Override
    public void stopParsing() {
        this.stopped = true;
    }

    @Override
    public boolean isStopped() {
        return this.stopped;
    }

    JsonProvider getJsonProvider() {
        return jsonProvider;
    }

    void setJsonProvider(JsonProvider jsonProvider) {
        this.jsonProvider = jsonProvider;
    }

    ErrorHandlingStrategy getErrorHandlingStrategy() {
        return errorHandlingStrategy;
    }

    void setErrorHandlingStrategy(ErrorHandlingStrategy errorHandlingStrategy) {
        this.errorHandlingStrategy = errorHandlingStrategy;
    }
}
