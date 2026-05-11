package play.plugins.openapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.NumberSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import play.Logger;
import play.mvc.Router;

import java.beans.Introspector;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates an OpenAPI 3.x specification from Play's in-memory route table
 * ({@link Router#routes}) and reflection on controller method signatures.
 *
 * <p>This is a best-effort, non-exhaustive mapping. The goal is to produce a
 * useful first cut so consumers (Swagger UI, codegen tools) have something to
 * work with for any Play app, without requiring developers to annotate their
 * controllers. Routes that cannot be classified cleanly still appear with
 * minimal info rather than being silently dropped.
 *
 * <p>Annotation-driven enrichment (via {@code io.swagger.v3.oas.annotations}) is
 * applied on top of inferred values by {@link OpenApiAnnotationReader}: see
 * PF-81 for the supported annotations and merge-precedence rules.
 */
public class OpenApiGenerator {

    /**
     * Matches Play path parameters: {id}, {<[0-9]+>id}.
     * Group 1 captures the parameter name (after an optional regex constraint).
     */
    private static final Pattern PATH_PARAM_PATTERN =
            Pattern.compile("\\{(?:<[^>]+>)?([a-zA-Z_][a-zA-Z_0-9]*)\\}");

    /**
     * Routes flagged as static-asset or non-action handlers; we skip these
     * since they don't correspond to a callable controller method.
     */
    private static final Set<String> NON_ACTION_PREFIXES = Set.of(
            "staticDir:", "staticFile:", "404", "WS"
    );

    private final ClassLoader classLoader;
    private final String title;
    private final String version;
    private final OpenApiAnnotationReader annotationReader;

    // Per-generate() state. Reset at the start of each generate() call so the
    // generator is reusable across multiple spec builds. Not thread-safe;
    // OpenApiPlugin creates a fresh generator per spec request anyway.
    private OpenAPI currentSpec;
    private Map<Class<?>, String> componentNames;
    private Set<String> namesInUse;

    public OpenApiGenerator(ClassLoader classLoader, String title, String version) {
        this.classLoader = classLoader;
        this.title = title;
        this.version = version;
        this.annotationReader = new OpenApiAnnotationReader(this);
    }

    /**
     * Build an OpenAPI document from the supplied list of routes.
     */
    public OpenAPI generate(List<Router.Route> routes) {
        currentSpec = new OpenAPI();
        componentNames = new HashMap<>();
        namesInUse = new HashSet<>();

        currentSpec.setInfo(new Info()
                .title(title == null || title.isBlank() ? "Play Application API" : title)
                .version(version == null || version.isBlank() ? "1.0.0" : version)
                .description("Generated from Play's routes file."));
        currentSpec.setComponents(new Components());

        Paths paths = new Paths();
        for (Router.Route route : routes) {
            if (!isActionRoute(route)) {
                continue;
            }
            String openApiPath = route.path;
            if (openApiPath == null || openApiPath.isBlank()) {
                continue;
            }

            PathItem pathItem = paths.computeIfAbsent(openApiPath, p -> new PathItem());
            Operation op = buildOperation(route);
            assignOperation(pathItem, route.method, op);
        }
        currentSpec.setPaths(paths);
        return currentSpec;
    }

    private boolean isActionRoute(Router.Route route) {
        if (route == null || route.action == null) {
            return false;
        }
        String action = route.action;
        for (String prefix : NON_ACTION_PREFIXES) {
            if (action.startsWith(prefix) || action.equals(prefix)) {
                return false;
            }
        }
        // The action must look like Controller.method
        return action.contains(".");
    }

    private Operation buildOperation(Router.Route route) {
        Operation op = new Operation();
        op.setOperationId(route.action);
        op.setSummary(route.method + " " + route.path);
        op.addTagsItem(deriveTag(route.action));

        Set<String> pathParamNames = extractPathParamNames(route.path);

        Method actionMethod = resolveActionMethod(route.action);
        if (actionMethod != null) {
            buildParameters(op, actionMethod, pathParamNames, route.method);
            op.setResponses(buildResponses(actionMethod));
            // PF-81: merge Swagger annotations on top of inferred values.
            // Annotation-supplied values win; tags are unioned (class-level
            // @Tag is the controller-wide grouping, method-level adds finer-
            // grained tags).
            annotationReader.apply(op, actionMethod);
        } else {
            // Reflective lookup failed (controller not on classpath, name mismatch, etc.).
            // Still emit any path parameters we discovered from the URL pattern so the
            // route is not silently dropped.
            for (String paramName : pathParamNames) {
                op.addParametersItem(new Parameter()
                        .name(paramName)
                        .in("path")
                        .required(true)
                        .schema(new StringSchema()));
            }
            op.setResponses(defaultResponses());
        }
        return op;
    }

    private static String deriveTag(String action) {
        int dot = action.lastIndexOf('.');
        if (dot <= 0) {
            return "default";
        }
        String controller = action.substring(0, dot);
        // Strip leading "controllers." for readability.
        if (controller.startsWith("controllers.")) {
            controller = controller.substring("controllers.".length());
        }
        return controller;
    }

    private static Set<String> extractPathParamNames(String path) {
        Set<String> names = new LinkedHashSet<>();
        if (path == null) {
            return names;
        }
        Matcher m = PATH_PARAM_PATTERN.matcher(path);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    /**
     * Resolve a route action name (e.g. "Application.index" or "controllers.Application.index")
     * to a {@link Method}. Returns null on any failure — callers should treat that as
     * "no reflection-derived metadata available" rather than an error.
     */
    Method resolveActionMethod(String action) {
        try {
            String full = action.startsWith("controllers.") ? action : "controllers." + action;
            int lastDot = full.lastIndexOf('.');
            if (lastDot < 0) {
                return null;
            }
            String controllerName = full.substring(0, lastDot);
            String methodName = full.substring(lastDot + 1);
            Class<?> controllerClass;
            try {
                controllerClass = Class.forName(controllerName, false, classLoader);
            } catch (ClassNotFoundException e) {
                return null;
            }
            // Match by case-insensitive name (Play allows controllerName casing flex);
            // pick the first public method matching the name.
            Method best = null;
            for (Method m : controllerClass.getDeclaredMethods()) {
                if (m.getName().equalsIgnoreCase(methodName)
                        && java.lang.reflect.Modifier.isPublic(m.getModifiers())) {
                    if (best == null || m.getParameterCount() > best.getParameterCount()) {
                        best = m;
                    }
                }
            }
            return best;
        } catch (Throwable t) {
            Logger.trace("OpenApiGenerator: failed to resolve action %s: %s", action, t);
            return null;
        }
    }

    private void buildParameters(Operation op, Method actionMethod, Set<String> pathParamNames, String httpMethod) {
        java.lang.reflect.Parameter[] reflParams = actionMethod.getParameters();
        boolean isBodyMethod = "POST".equalsIgnoreCase(httpMethod)
                || "PUT".equalsIgnoreCase(httpMethod)
                || "PATCH".equalsIgnoreCase(httpMethod);

        // Resolve effective parameter names. javac without -parameters yields synthetic
        // names (arg0, arg1, ...). When the synthetic count matches the number of path
        // parameters (or the path params are a prefix of the method's params), fall back
        // to positional assignment so the path params are still recognised correctly.
        String[] effectiveNames = new String[reflParams.length];
        boolean namesPresent = reflParams.length == 0 || reflParams[0].isNamePresent();
        List<String> orderedPathParams = pathParamNames.stream().toList();
        for (int i = 0; i < reflParams.length; i++) {
            if (namesPresent) {
                effectiveNames[i] = reflParams[i].getName();
            } else if (i < orderedPathParams.size()) {
                effectiveNames[i] = orderedPathParams.get(i);
            } else {
                effectiveNames[i] = reflParams[i].getName();
            }
        }

        Set<String> bound = new HashSet<>();
        for (int i = 0; i < reflParams.length; i++) {
            java.lang.reflect.Parameter rp = reflParams[i];
            String name = effectiveNames[i];
            Schema<?> schema = schemaFor(rp.getParameterizedType());
            if (pathParamNames.contains(name)) {
                op.addParametersItem(new Parameter()
                        .name(name)
                        .in("path")
                        .required(true)
                        .schema(schema));
                bound.add(name);
            } else if (isBodyMethod && isComplexType(rp.getType())) {
                // Bind first complex (non-primitive, non-String) param of a body method as the request body.
                if (op.getRequestBody() == null) {
                    Content content = new Content().addMediaType("application/json",
                            new MediaType().schema(schema));
                    op.setRequestBody(new RequestBody().content(content).required(true));
                    bound.add(name);
                }
            } else {
                op.addParametersItem(new Parameter()
                        .name(name)
                        .in("query")
                        .required(false)
                        .schema(schema));
                bound.add(name);
            }
        }
        // Path params declared in URL but not present on the method (rare —
        // happens with action-less routes or hand-built tests). Add as strings.
        for (String pp : pathParamNames) {
            if (!bound.contains(pp)) {
                op.addParametersItem(new Parameter()
                        .name(pp)
                        .in("path")
                        .required(true)
                        .schema(new StringSchema()));
            }
        }
    }

    private static boolean isComplexType(Class<?> type) {
        if (type.isPrimitive()) return false;
        if (type == String.class) return false;
        if (Number.class.isAssignableFrom(type)) return false;
        if (type == Boolean.class || type == Character.class) return false;
        if (type.isEnum()) return false;
        return true;
    }

    private ApiResponses buildResponses(Method actionMethod) {
        ApiResponses responses = new ApiResponses();
        Type returnType = actionMethod.getGenericReturnType();
        if (returnType == void.class || returnType == Void.class) {
            // Most Play actions return void and emit via renderXxx() — we cannot infer
            // the response shape from the signature alone. Emit a generic 200 response.
            responses.addApiResponse("200", new ApiResponse()
                    .description("OK")
                    .content(new Content().addMediaType("*/*", new MediaType())));
            return responses;
        }
        Schema<?> schema = schemaFor(returnType);
        Content content = new Content().addMediaType("application/json",
                new MediaType().schema(schema));
        responses.addApiResponse("200", new ApiResponse().description("OK").content(content));
        return responses;
    }

    private static ApiResponses defaultResponses() {
        ApiResponses responses = new ApiResponses();
        responses.addApiResponse("200", new ApiResponse()
                .description("OK")
                .content(new Content().addMediaType("*/*", new MediaType())));
        return responses;
    }

    /**
     * Map a Java type to an OpenAPI Schema. Beans recurse into their public,
     * non-static, non-transient fields (PF-97) and are registered as components
     * with a $ref returned in their place. Collections preserve their element
     * type; maps and unrecognized parameterized types degrade to ObjectSchema.
     */
    Schema<?> schemaFor(Type type) {
        if (type instanceof Class<?> cls) {
            return classSchema(cls);
        }
        if (type instanceof ParameterizedType pt) {
            Type raw = pt.getRawType();
            if (raw instanceof Class<?> rawCls) {
                // PF-100: Optional<T> unwraps to schemaFor(T) with nullable: true.
                // The nullable sibling on a $ref is OpenAPI-3.1 clean and tolerated by
                // 3.0 consumers per the spec's "additional properties on $ref" allowance.
                if (rawCls == Optional.class) {
                    Schema<?> inner = schemaFor(pt.getActualTypeArguments()[0]);
                    inner.setNullable(true);
                    return inner;
                }
                if (Collection.class.isAssignableFrom(rawCls)) {
                    Type elem = pt.getActualTypeArguments()[0];
                    return new ArraySchema().items(schemaFor(elem));
                }
                if (Map.class.isAssignableFrom(rawCls)) {
                    // PF-100: preserve V through additionalProperties. K is intentionally
                    // ignored — OpenAPI 3 treats map keys as strings by default.
                    Type[] args = pt.getActualTypeArguments();
                    if (args.length >= 2) {
                        return new ObjectSchema().additionalProperties(schemaFor(args[1]));
                    }
                    return new ObjectSchema();
                }
                return classSchema(rawCls);
            }
        }
        return new ObjectSchema();
    }

    private Schema<?> classSchema(Class<?> cls) {
        // PF-102: JSON-library tree types (Gson JsonElement, Jackson JsonNode,
        // org.json) are opaque "JSON of unspecified shape" containers — recursing
        // into them surfaces the library's API methods (asString, isJsonArray,
        // etc.) as schema properties, which is pure noise. Short-circuit early.
        if (isOpaqueJsonTreeType(cls)) {
            return new ObjectSchema();
        }
        if (cls == String.class || cls == Character.class || cls == char.class) {
            return new StringSchema();
        }
        if (cls == Boolean.class || cls == boolean.class) {
            return new BooleanSchema();
        }
        if (cls == Integer.class || cls == int.class
                || cls == Long.class || cls == long.class
                || cls == Short.class || cls == short.class
                || cls == Byte.class || cls == byte.class) {
            return new IntegerSchema();
        }
        if (cls == Float.class || cls == float.class
                || cls == Double.class || cls == double.class) {
            return new NumberSchema();
        }
        // PF-100: common stdlib value types — explicit OpenAPI mapping before the
        // bean-recursion fall-through (which would otherwise short-circuit them
        // via isStdLib and emit a useless `{type: object}`).
        Schema<?> stdlib = stdlibTypeSchema(cls);
        if (stdlib != null) {
            return stdlib;
        }
        if (cls.isArray()) {
            return new ArraySchema().items(classSchema(cls.getComponentType()));
        }
        if (Collection.class.isAssignableFrom(cls)) {
            return new ArraySchema().items(new ObjectSchema());
        }
        if (Map.class.isAssignableFrom(cls)) {
            return new ObjectSchema();
        }
        if (cls.isEnum()) {
            StringSchema enumSchema = new StringSchema();
            for (Object constant : cls.getEnumConstants()) {
                enumSchema.addEnumItem(constant.toString());
            }
            return enumSchema;
        }
        // PF-97: recurse into bean properties and register the result under
        // components/schemas so subsequent references share one definition.
        return beanSchema(cls);
    }

    /**
     * Build (and cache under {@code components/schemas/<name>}) a schema for a
     * Java bean. Iterates public, non-static, non-transient fields and recurses
     * via {@link #schemaFor(Type)} so generics and nested beans are preserved.
     *
     * <p>Cycle break: the component name is reserved in {@code componentNames}
     * <em>before</em> descending into fields, so a recursive reference to the
     * same class short-circuits to a $ref instead of looping.
     *
     * <p>Simple-name collisions: the first class with a given simple name claims
     * that key; later classes register under their canonical (package-qualified)
     * name to disambiguate.
     *
     * <p>Stdlib types ({@code java.*}, {@code javax.*}, {@code jakarta.*}) and
     * anonymous classes degrade to a plain {@link ObjectSchema} — registering
     * them under components would just be noise.
     */
    private Schema<?> beanSchema(Class<?> cls) {
        if (isStdLib(cls) || cls.getSimpleName().isEmpty()) {
            return new ObjectSchema();
        }
        String existingName = componentNames.get(cls);
        if (existingName != null) {
            return refSchema(existingName);
        }

        String name = pickComponentName(cls);
        componentNames.put(cls, name);
        namesInUse.add(name);

        ObjectSchema bean = buildObjectSchema(cls);
        currentSpec.getComponents().addSchemas(name, bean);
        return refSchema(name);
    }

    private String pickComponentName(Class<?> cls) {
        String simple = cls.getSimpleName();
        if (!namesInUse.contains(simple)) {
            return simple;
        }
        String canonical = cls.getCanonicalName();
        return canonical != null ? canonical : cls.getName();
    }

    private ObjectSchema buildObjectSchema(Class<?> cls) {
        ObjectSchema obj = new ObjectSchema();
        List<String> required = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        if (cls.isRecord()) {
            // PF-99: records are fully described by their components; skip field/getter passes
            // so we don't double-up via the synthetic private field + accessor a record exposes.
            // Annotations may live on either the component itself (canonical constructor param)
            // or the accessor method — both are consulted via the secondary AnnotatedElement.
            for (RecordComponent rc : cls.getRecordComponents()) {
                addProperty(obj, required, seen, rc.getName(), rc.getGenericType(), rc, rc.getAccessor());
            }
        } else {
            // PF-97: public field reflection — runs first so fields win on dedup against getters.
            for (Field field : cls.getFields()) {
                if (shouldSkipField(field)) {
                    continue;
                }
                addProperty(obj, required, seen, field.getName(), field.getGenericType(), field, null);
            }
            // PF-98: JavaBean getter reflection — fills gaps left by the field pass. Applies
            // to both classes and interfaces (the latter have no fields by definition).
            for (Method method : cls.getMethods()) {
                String derived = deriveGetterPropertyName(method);
                if (derived == null) {
                    continue;
                }
                addProperty(obj, required, seen, derived, method.getGenericReturnType(), method, null);
            }
        }

        if (!required.isEmpty()) {
            obj.setRequired(required);
        }
        return obj;
    }

    /**
     * Add a property to {@code obj}, applying rename annotations (JsonProperty,
     * SerializedName) and skip annotations (JsonIgnore) before the lookup, then
     * Schema enrichment + required-tracking on top.
     *
     * @param primary   the property's primary annotation source (Field, Method, or RecordComponent)
     * @param secondary optional second source consulted for annotations; used for records to also
     *                  inspect the accessor method when the component itself isn't annotated.
     *                  null for fields and getters.
     */
    private void addProperty(ObjectSchema obj, List<String> required, Set<String> seen,
                              String baseName, Type valueType,
                              AnnotatedElement primary, AnnotatedElement secondary) {
        if (findAnnotationBySimpleName(primary, "JsonIgnore") != null) {
            return;
        }
        if (secondary != null && findAnnotationBySimpleName(secondary, "JsonIgnore") != null) {
            return;
        }

        String name = baseName;
        String renamed = readRename(primary);
        if (renamed == null && secondary != null) {
            renamed = readRename(secondary);
        }
        if (renamed != null) {
            name = renamed;
        }
        if (seen.contains(name)) {
            return;
        }

        Schema<?> propSchema = schemaFor(valueType);
        applySchemaAnnotation(propSchema, primary, required, name);
        if (secondary != null) {
            applySchemaAnnotation(propSchema, secondary, required, name);
        }
        applyJsonPropertyRequired(primary, required, name);
        if (secondary != null) {
            applyJsonPropertyRequired(secondary, required, name);
        }

        obj.addProperty(name, propSchema);
        seen.add(name);
    }

    private static String readRename(AnnotatedElement e) {
        Annotation jp = findAnnotationBySimpleName(e, "JsonProperty");
        if (jp != null) {
            Object val = readAnnotationAttribute(jp, "value");
            if (val instanceof String s && !s.isEmpty()) {
                return s;
            }
        }
        Annotation sn = findAnnotationBySimpleName(e, "SerializedName");
        if (sn != null) {
            Object val = readAnnotationAttribute(sn, "value");
            if (val instanceof String s && !s.isEmpty()) {
                return s;
            }
        }
        return null;
    }

    /**
     * Derive a JavaBean property name from a getter method, or return null if
     * the method is not a JavaBean getter. Uses {@link Introspector#decapitalize}
     * so {@code getURL()} maps to {@code URL} (acronym preserved) while
     * {@code getName()} maps to {@code name}.
     */
    private static String deriveGetterPropertyName(Method m) {
        // Exclude getClass() and other Object-inherited methods. getClass() is the
        // only one that actually matches the getX-pattern checks below; the rest
        // (hashCode, toString, equals, wait, notify, notifyAll) fail the name or
        // signature checks. The declaring-class check is the cleanest guard.
        if (m.getDeclaringClass() == Object.class) {
            return null;
        }
        int mod = m.getModifiers();
        if (!Modifier.isPublic(mod) || Modifier.isStatic(mod)) {
            return null;
        }
        if (m.getParameterCount() != 0) {
            return null;
        }
        Class<?> ret = m.getReturnType();
        if (ret == void.class) {
            return null;
        }
        String name = m.getName();
        if (name.startsWith("get") && name.length() > 3) {
            return Introspector.decapitalize(name.substring(3));
        }
        if (name.startsWith("is") && name.length() > 2
                && (ret == boolean.class || ret == Boolean.class)) {
            return Introspector.decapitalize(name.substring(2));
        }
        return null;
    }

    private static boolean shouldSkipField(Field f) {
        int mod = f.getModifiers();
        if (Modifier.isStatic(mod) || Modifier.isTransient(mod)) {
            return true;
        }
        String name = f.getName();
        // EclipseLink JPA enhancement leaks `_persistence_*`; ASM-generated
        // accessors leak `__*`. Both are runtime artefacts, never part of the
        // intended serialization shape.
        if (name.startsWith("_persistence_") || name.startsWith("__")) {
            return true;
        }
        if (findAnnotationBySimpleName(f, "Transient") != null) {
            return true;
        }
        if (findAnnotationBySimpleName(f, "JsonIgnore") != null) {
            return true;
        }
        Annotation expose = findAnnotationBySimpleName(f, "Expose");
        if (expose != null) {
            Object serialize = readAnnotationAttribute(expose, "serialize");
            if (serialize instanceof Boolean b && !b) {
                return true;
            }
        }
        return false;
    }

    private static void applySchemaAnnotation(Schema<?> schema, AnnotatedElement e,
                                                List<String> required, String propName) {
        io.swagger.v3.oas.annotations.media.Schema ann =
                e.getAnnotation(io.swagger.v3.oas.annotations.media.Schema.class);
        if (ann == null) {
            return;
        }
        if (!ann.description().isEmpty()) schema.setDescription(ann.description());
        if (!ann.example().isEmpty()) schema.setExample(ann.example());
        if (!ann.format().isEmpty()) schema.setFormat(ann.format());
        if (!ann.pattern().isEmpty()) schema.setPattern(ann.pattern());
        if (ann.nullable()) schema.setNullable(true);
        if (ann.deprecated()) schema.setDeprecated(true);
        if (!ann.minimum().isEmpty()) {
            try {
                schema.setMinimum(new BigDecimal(ann.minimum()));
            } catch (NumberFormatException ignored) {
                // Malformed numeric constraint: leave the inferred value alone.
            }
        }
        if (!ann.maximum().isEmpty()) {
            try {
                schema.setMaximum(new BigDecimal(ann.maximum()));
            } catch (NumberFormatException ignored) {
            }
        }
        if (ann.required()
                || ann.requiredMode() == io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED) {
            if (!required.contains(propName)) {
                required.add(propName);
            }
        }
    }

    private static void applyJsonPropertyRequired(AnnotatedElement e, List<String> required, String propName) {
        Annotation ann = findAnnotationBySimpleName(e, "JsonProperty");
        if (ann == null) {
            return;
        }
        Object req = readAnnotationAttribute(ann, "required");
        if (req instanceof Boolean b && b && !required.contains(propName)) {
            required.add(propName);
        }
    }

    private static Schema<?> refSchema(String name) {
        return new Schema<Object>().$ref("#/components/schemas/" + name);
    }

    /**
     * Detect JSON-library tree types that should render as opaque {@code object}
     * schemas instead of being recursed into. Detection is by fully-qualified
     * class name (and superclass-chain walk for Jackson's {@code *Node} family),
     * so neither Jackson nor json.org becomes a framework compile-time dep.
     *
     * <p>Covered:
     * <ul>
     *   <li>Gson: {@code JsonElement}, {@code JsonObject}, {@code JsonArray},
     *       {@code JsonPrimitive}, {@code JsonNull} (explicit list — Gson has
     *       exactly these four subclasses)</li>
     *   <li>Jackson: {@code JsonNode} and any subclass (via superclass walk —
     *       covers {@code ArrayNode}, {@code ObjectNode}, {@code TextNode}, etc.)</li>
     *   <li>json.org: {@code JSONObject}, {@code JSONArray}</li>
     * </ul>
     */
    private static boolean isOpaqueJsonTreeType(Class<?> cls) {
        String name = cls.getName();
        if (name.equals("com.google.gson.JsonElement")
                || name.equals("com.google.gson.JsonObject")
                || name.equals("com.google.gson.JsonArray")
                || name.equals("com.google.gson.JsonPrimitive")
                || name.equals("com.google.gson.JsonNull")) {
            return true;
        }
        if (name.equals("org.json.JSONObject") || name.equals("org.json.JSONArray")) {
            return true;
        }
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            if (c.getName().equals("com.fasterxml.jackson.databind.JsonNode")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Map common stdlib value types to typed-string / typed-number schemas.
     * Returns null when the class isn't in the table — the caller continues
     * through the usual array / collection / bean-recursion path.
     *
     * <p>Format strings follow OpenAPI 3 conventions: {@code uuid}, {@code uri},
     * {@code date}, {@code date-time}, {@code time}. ISO-8601 durations and
     * arbitrary-precision numbers have no standard OpenAPI format string, so
     * they emit the base type only.
     */
    private static Schema<?> stdlibTypeSchema(Class<?> cls) {
        if (cls == java.util.UUID.class) {
            return new StringSchema().format("uuid");
        }
        if (cls == java.net.URI.class || cls == java.net.URL.class) {
            return new StringSchema().format("uri");
        }
        if (cls == java.util.Date.class
                || cls == java.sql.Date.class
                || cls == java.sql.Timestamp.class
                || cls == java.time.Instant.class
                || cls == java.time.OffsetDateTime.class
                || cls == java.time.ZonedDateTime.class
                || cls == java.time.LocalDateTime.class) {
            return new StringSchema().format("date-time");
        }
        if (cls == java.time.LocalDate.class) {
            return new StringSchema().format("date");
        }
        if (cls == java.time.LocalTime.class || cls == java.time.OffsetTime.class) {
            return new StringSchema().format("time");
        }
        if (cls == java.time.Duration.class || cls == java.time.Period.class) {
            return new StringSchema();
        }
        if (cls == java.math.BigDecimal.class || cls == java.math.BigInteger.class) {
            return new NumberSchema();
        }
        return null;
    }

    private static boolean isStdLib(Class<?> cls) {
        String pkg = cls.getPackageName();
        return pkg.startsWith("java.") || pkg.equals("java")
                || pkg.startsWith("javax.") || pkg.equals("javax")
                || pkg.startsWith("jakarta.") || pkg.equals("jakarta");
    }

    /**
     * Find an annotation on {@code element} by its simple name (so detection
     * works without the annotation's defining library on the compile-time
     * classpath — used for Jackson/Gson interop).
     */
    private static Annotation findAnnotationBySimpleName(AnnotatedElement element, String simpleName) {
        for (Annotation a : element.getAnnotations()) {
            if (a.annotationType().getSimpleName().equals(simpleName)) {
                return a;
            }
        }
        return null;
    }

    private static Object readAnnotationAttribute(Annotation a, String name) {
        try {
            Method m = a.annotationType().getMethod(name);
            return m.invoke(a);
        } catch (Exception e) {
            return null;
        }
    }

    private static void assignOperation(PathItem pathItem, String method, Operation op) {
        if (method == null) {
            return;
        }
        switch (method.toUpperCase()) {
            case "GET" -> pathItem.setGet(op);
            case "POST" -> pathItem.setPost(op);
            case "PUT" -> pathItem.setPut(op);
            case "DELETE" -> pathItem.setDelete(op);
            case "PATCH" -> pathItem.setPatch(op);
            case "HEAD" -> pathItem.setHead(op);
            case "OPTIONS" -> pathItem.setOptions(op);
            case "*" -> {
                pathItem.setGet(op);
                pathItem.setPost(op);
                pathItem.setPut(op);
                pathItem.setDelete(op);
                pathItem.setPatch(op);
            }
            default -> Logger.trace("OpenApiGenerator: unsupported HTTP method %s", method);
        }
    }
}
