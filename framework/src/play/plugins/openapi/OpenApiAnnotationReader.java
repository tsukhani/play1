package play.plugins.openapi;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads Swagger {@code io.swagger.v3.oas.annotations.*} annotations off a
 * controller class/method and merges them into an already-inferred
 * {@link Operation} produced by {@link OpenApiGenerator}.
 *
 * <p>Precedence rule: annotation-supplied values WIN over inferred values
 * (the developer is overriding deliberately). Two exceptions intentionally
 * preserve inferred output:
 * <ul>
 *   <li>Tags are <em>unioned</em>, not replaced — the class-level
 *       {@code @Tag} is the controller-wide grouping; method-level
 *       {@code @Operation(tags=...)} adds finer-grained tags.</li>
 *   <li>Per-status-code responses from {@code @ApiResponse} merge into the
 *       responses map; codes not supplied by annotations keep the inferred
 *       (typically a 200 from the return-type schema).</li>
 * </ul>
 *
 * <p>Used only for PF-81 enrichment; controllers with no Swagger annotations
 * produce identical output to PF-12's inference-only path.
 */
final class OpenApiAnnotationReader {

    private final OpenApiGenerator generator;

    OpenApiAnnotationReader(OpenApiGenerator generator) {
        this.generator = generator;
    }

    /**
     * Apply class-level and method-level Swagger annotations onto {@code op}.
     * No-op when the method/class has no relevant annotations.
     */
    void apply(Operation op, Method method) {
        applyClassTag(op, method.getDeclaringClass());
        applyOperation(op, method);
        applyParameters(op, method);
        applyRequestBody(op, method);
        applyApiResponses(op, method);
    }

    private void applyClassTag(Operation op, Class<?> declaringClass) {
        io.swagger.v3.oas.annotations.tags.Tag classTag =
                declaringClass.getAnnotation(io.swagger.v3.oas.annotations.tags.Tag.class);
        if (classTag == null || classTag.name().isEmpty()) {
            return;
        }
        unionTag(op, classTag.name());
    }

    private void applyOperation(Operation op, Method method) {
        io.swagger.v3.oas.annotations.Operation ann =
                method.getAnnotation(io.swagger.v3.oas.annotations.Operation.class);
        if (ann == null) {
            return;
        }
        if (!ann.summary().isEmpty()) {
            op.setSummary(ann.summary());
        }
        if (!ann.description().isEmpty()) {
            op.setDescription(ann.description());
        }
        if (!ann.operationId().isEmpty()) {
            op.setOperationId(ann.operationId());
        }
        for (String tag : ann.tags()) {
            if (tag != null && !tag.isEmpty()) {
                unionTag(op, tag);
            }
        }
        // @Operation can carry nested parameters/requestBody/responses too. We
        // delegate those to the dedicated handlers below so they share merge
        // logic with the standalone annotations.
        for (io.swagger.v3.oas.annotations.Parameter p : ann.parameters()) {
            mergeParameter(op, p);
        }
        if (!isEmptyRequestBody(ann.requestBody())) {
            op.setRequestBody(toRequestBody(ann.requestBody()));
        }
        for (io.swagger.v3.oas.annotations.responses.ApiResponse r : ann.responses()) {
            mergeApiResponse(op, r);
        }
    }

    private void applyParameters(Operation op, Method method) {
        for (java.lang.reflect.Parameter rp : method.getParameters()) {
            io.swagger.v3.oas.annotations.Parameter ann =
                    rp.getAnnotation(io.swagger.v3.oas.annotations.Parameter.class);
            if (ann == null) {
                continue;
            }
            // Resolve the effective name: annotation wins, else reflection name.
            String name = !ann.name().isEmpty() ? ann.name() : rp.getName();
            mergeParameter(op, ann, name);
        }
    }

    private void applyRequestBody(Operation op, Method method) {
        io.swagger.v3.oas.annotations.parameters.RequestBody ann =
                method.getAnnotation(io.swagger.v3.oas.annotations.parameters.RequestBody.class);
        if (ann == null) {
            return;
        }
        op.setRequestBody(toRequestBody(ann));
    }

    private void applyApiResponses(Operation op, Method method) {
        io.swagger.v3.oas.annotations.responses.ApiResponses container =
                method.getAnnotation(io.swagger.v3.oas.annotations.responses.ApiResponses.class);
        if (container != null) {
            for (io.swagger.v3.oas.annotations.responses.ApiResponse r : container.value()) {
                mergeApiResponse(op, r);
            }
        }
        io.swagger.v3.oas.annotations.responses.ApiResponse single =
                method.getAnnotation(io.swagger.v3.oas.annotations.responses.ApiResponse.class);
        if (single != null) {
            mergeApiResponse(op, single);
        }
    }

    // -- merge helpers -------------------------------------------------------

    private void mergeParameter(Operation op, io.swagger.v3.oas.annotations.Parameter ann) {
        if (ann.name().isEmpty()) {
            return;
        }
        mergeParameter(op, ann, ann.name());
    }

    private void mergeParameter(Operation op, io.swagger.v3.oas.annotations.Parameter ann, String name) {
        Parameter target = findParameter(op, name);
        if (target == null) {
            // Annotation references a parameter not present in the inferred
            // list. Add it; consumers expect the annotation to be authoritative.
            target = new Parameter().name(name);
            op.addParametersItem(target);
        }
        if (!ann.description().isEmpty()) {
            target.setDescription(ann.description());
        }
        if (ann.required()) {
            target.setRequired(true);
        }
        if (ann.in() != null && ann.in() != io.swagger.v3.oas.annotations.enums.ParameterIn.DEFAULT) {
            target.setIn(ann.in().toString());
        }
        Schema<?> annSchema = schemaFromAnnotation(ann.schema());
        if (annSchema != null) {
            target.setSchema(annSchema);
        }
    }

    private Parameter findParameter(Operation op, String name) {
        if (op.getParameters() == null) {
            return null;
        }
        for (Parameter p : op.getParameters()) {
            if (name.equals(p.getName())) {
                return p;
            }
        }
        return null;
    }

    private void mergeApiResponse(Operation op, io.swagger.v3.oas.annotations.responses.ApiResponse ann) {
        String code = ann.responseCode();
        if (code == null || code.isEmpty()) {
            return;
        }
        ApiResponses responses = op.getResponses();
        if (responses == null) {
            responses = new ApiResponses();
            op.setResponses(responses);
        }
        ApiResponse target = responses.get(code);
        if (target == null) {
            target = new ApiResponse();
            responses.addApiResponse(code, target);
        }
        if (!ann.description().isEmpty()) {
            target.setDescription(ann.description());
        }
        Content content = toContent(ann.content());
        if (content != null) {
            target.setContent(content);
        }
    }

    private RequestBody toRequestBody(io.swagger.v3.oas.annotations.parameters.RequestBody ann) {
        RequestBody body = new RequestBody();
        if (!ann.description().isEmpty()) {
            body.setDescription(ann.description());
        }
        if (ann.required()) {
            body.setRequired(true);
        }
        Content content = toContent(ann.content());
        if (content != null) {
            body.setContent(content);
        }
        return body;
    }

    private Content toContent(io.swagger.v3.oas.annotations.media.Content[] anns) {
        if (anns == null || anns.length == 0) {
            return null;
        }
        Content content = new Content();
        boolean any = false;
        for (io.swagger.v3.oas.annotations.media.Content c : anns) {
            String mediaType = c.mediaType().isEmpty() ? "application/json" : c.mediaType();
            MediaType mt = new MediaType();
            Schema<?> schema = schemaFromAnnotation(c.schema());
            if (schema != null) {
                mt.setSchema(schema);
            }
            content.addMediaType(mediaType, mt);
            any = true;
        }
        return any ? content : null;
    }

    /**
     * Produce a Schema from a {@code @Schema} annotation, or null if the
     * annotation is empty / placeholder. Currently honours
     * {@code implementation()} (mapped via the generator's classSchema path)
     * and {@code description()}; richer fields (constraints, examples, etc.)
     * are out of scope for the initial enrichment cut.
     */
    private Schema<?> schemaFromAnnotation(io.swagger.v3.oas.annotations.media.Schema ann) {
        if (ann == null) {
            return null;
        }
        Schema<?> schema = null;
        Class<?> impl = ann.implementation();
        if (impl != null && impl != Void.class) {
            schema = generator.schemaFor(impl);
        }
        if (!ann.description().isEmpty()) {
            if (schema == null) {
                schema = new io.swagger.v3.oas.models.media.ObjectSchema();
            }
            schema.setDescription(ann.description());
        }
        return schema;
    }

    private boolean isEmptyRequestBody(io.swagger.v3.oas.annotations.parameters.RequestBody rb) {
        if (rb == null) {
            return true;
        }
        return rb.description().isEmpty()
                && rb.content().length == 0
                && rb.ref().isEmpty();
    }

    private void unionTag(Operation op, String tag) {
        List<String> tags = op.getTags();
        Set<String> seen = tags == null ? new LinkedHashSet<>() : new LinkedHashSet<>(tags);
        if (seen.add(tag)) {
            op.setTags(List.copyOf(seen));
        }
    }
}
