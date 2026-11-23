package play.classloading;

import java.util.List;
import java.util.Set;

import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.tools.Diagnostic;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

/**
 * PF-242: Play's compile refuses a method reference to one of the finders of {@code play.db.jpa.GenericModel}, such
 * as {@code Book::findById}. Under javac and ECJ alike the method handle names the class that declares the method,
 * GenericModel, so the call reaches GenericModel's placeholder, which throws, and not the finder JPAEnhancer gives
 * each entity class; the entity's type is not in the bytecode for any enhancer to redirect it. A call such as
 * {@code Book.findById(id)}, in a lambda or anywhere else, names the entity and reaches its finder. So the reference is
 * reported where it is written, with the lambda to write instead, rather than failing when that path first runs.
 *
 * <p>Only Play's compile of the application has this listener: Gradle's {@code compileJava} is not changed. It is a
 * class of its own so that only a start that compiles loads it, with the {@code jdk.compiler} types it uses.
 */
final class FinderReferenceCheck implements TaskListener {

    private static final String GENERIC_MODEL = "play.db.jpa.GenericModel";

    /** The placeholders a method reference can reach: count, find and delete with a query, and the rest. */
    private static final Set<String> FINDERS = Set.of("count", "findAll", "all", "deleteAll", "findById", "find",
            "delete", "create");

    private final Trees trees;

    private FinderReferenceCheck(Trees trees) {
        this.trees = trees;
    }

    static void addTo(JavacTask task) {
        task.addTaskListener(new FinderReferenceCheck(Trees.instance(task)));
    }

    @Override
    public void finished(TaskEvent event) {
        // once per top-level class, after its attribution: its method references are resolved by then
        if (event.getKind() != TaskEvent.Kind.ANALYZE || event.getTypeElement() == null) {
            return;
        }
        TreePath path = trees.getPath(event.getTypeElement());
        if (path == null) {
            return;
        }
        CompilationUnitTree unit = path.getCompilationUnit();
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitMemberReference(MemberReferenceTree reference, Void unused) {
                Element target = trees.getElement(getCurrentPath());
                if (target instanceof ExecutableElement finder && isPlaceholder(finder)) {
                    trees.printMessage(Diagnostic.Kind.ERROR, message(reference, finder), reference, unit);
                }
                return super.visitMemberReference(reference, unused);
            }
        }.scan(path, null);
    }

    /** A static finder GenericModel declares, which only its subclasses' enhanced copies implement. */
    private static boolean isPlaceholder(ExecutableElement method) {
        String name = method.getSimpleName().toString();
        if (!FINDERS.contains(name) || !method.getModifiers().contains(Modifier.STATIC)
                || !(method.getEnclosingElement() instanceof TypeElement owner)
                || !owner.getQualifiedName().contentEquals(GENERIC_MODEL)) {
            return false;
        }
        List<? extends VariableElement> parameters = method.getParameters();
        // GenericModel also has static create(...) methods that work as they are: only create(String, Params) is one
        return !name.equals("create")
                || parameters.size() == 2 && parameters.get(1).asType().toString().equals("play.mvc.Scope.Params");
    }

    private static String message(MemberReferenceTree reference, ExecutableElement finder) {
        String qualifier = reference.getQualifierExpression().toString();
        String name = finder.getSimpleName().toString();
        // the lambda's parameters follow the overload the reference resolved to: count() or count(query, params)
        List<String> parameters = switch (finder.getParameters().size()) {
            case 0 -> List.of();
            case 1 -> List.of("id");
            default -> name.equals("create") ? List.of("name", "params") : List.of("query", "params");
        };
        String lambda = (parameters.size() == 1 ? parameters.get(0) : "(" + String.join(", ", parameters) + ")")
                + " -> " + qualifier + "." + name + "(" + String.join(", ", parameters) + ")";
        return "A method reference cannot call an entity's finder: " + qualifier + "::" + name + " names "
                + "GenericModel's " + name + ", which throws, and not the one Play adds to the entity. "
                + "Write a lambda instead: " + lambda;
    }
}
