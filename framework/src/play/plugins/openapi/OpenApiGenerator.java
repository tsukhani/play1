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

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
    private final OpenApiAnnotationReader annotationReader;

    public OpenApiGenerator(ClassLoader classLoader, String title) {
        this.classLoader = classLoader;
        this.title = title;
        this.annotationReader = new OpenApiAnnotationReader(this);
    }

    /**
     * Build an OpenAPI document from the supplied list of routes.
     */
    public OpenAPI generate(List<Router.Route> routes) {
        OpenAPI openApi = new OpenAPI();
        openApi.setInfo(new Info()
                .title(title == null || title.isBlank() ? "Play Application API" : title)
                .version("1.0.0")
                .description("Generated from Play's routes file."));
        openApi.setComponents(new Components());

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
        openApi.setPaths(paths);
        return openApi;
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
     * Map a Java type to a basic OpenAPI Schema. Nested/generic types collapse to
     * their raw form; we don't recurse into bean properties (annotation-driven
     * enrichment is out of scope for this initial cut).
     */
    Schema<?> schemaFor(Type type) {
        if (type instanceof Class<?> cls) {
            return classSchema(cls);
        }
        if (type instanceof ParameterizedType pt) {
            Type raw = pt.getRawType();
            if (raw instanceof Class<?> rawCls) {
                if (Collection.class.isAssignableFrom(rawCls)) {
                    Type elem = pt.getActualTypeArguments()[0];
                    return new ArraySchema().items(schemaFor(elem));
                }
                if (Map.class.isAssignableFrom(rawCls)) {
                    return new ObjectSchema();
                }
                return classSchema(rawCls);
            }
        }
        return new ObjectSchema();
    }

    private static Schema<?> classSchema(Class<?> cls) {
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
        // Generic Java bean — no annotation-driven recursion in this cut.
        return new ObjectSchema();
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
