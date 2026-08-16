package sdd.index.streams;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import sdd.index.source.SourceParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The two axes of a stream registration, read out of the Java builders that produce it.
 *
 * <p>The shape this reads is a builder method returning a {@code StreamDescriptor} assembled from
 * locally-declared parts — the estate's {@code CanonicalDescriptors}. Nothing is resolved through
 * a symbol solver: a builder is a self-contained method by construction (that is what makes it the
 * single source of a descriptor), so following its own local variables is enough and keeps the
 * extractor working on a tree that does not compile.
 *
 * <p>Arguments are matched BY TYPE, never by position. {@code new StreamDescriptor(…)} takes
 * eleven arguments and half of them are {@code null} at any given call; a positional rule would
 * silently read the wrong one the first time a field is inserted, and read it as a fact.
 */
public final class StreamDescriptorExtractor {

    /** The class names this reads. Written out rather than resolved: the extractor is deliberately
     *  about ONE shape, and a name that no longer exists must produce nothing rather than
     *  something else. */
    private static final String DESCRIPTOR = "StreamDescriptor";
    private static final String KEY_SPEC = "KeySpec";
    private static final String KEY_FIELD = "KeyField";
    private static final String CHANNEL_BINDING = "ChannelBinding";
    private static final String FRAME_TYPE = "FrameType";

    /**
     * @param stream   the stream's own name, e.g. {@code md}
     * @param key      the key field names, IN ORDER — order decides how a subscription key is
     *                 encoded, so two ends agreeing on the set but not the order never match
     * @param channels the channels' frame types in order; an entry is null where the frame type is
     *                 not a literal, which is a real shape ({@code order}'s channel is
     *                 discriminated by a payload field) and not a parse failure
     */
    public record Descriptor(String stream, List<String> key, List<String> channels) {
        public Descriptor {
            key = List.copyOf(key);
            // Not List.copyOf: a null entry is meaningful here — it is a channel whose frame type
            // is decided by the payload — and List.copyOf rejects nulls outright.
            channels = java.util.Collections.unmodifiableList(new ArrayList<>(channels));
        }
    }

    private StreamDescriptorExtractor() {
    }

    /** Every descriptor any builder in the session produces, in source order. */
    public static List<Descriptor> extract(SourceParser.Session session) {
        List<Descriptor> descriptors = new ArrayList<>();
        for (SourceParser.ParsedUnit unit : session.units()) {
            unit.cu().findAll(MethodDeclaration.class).forEach(method ->
                    fromMethod(method).ifPresent(descriptors::add));
        }
        return descriptors;
    }

    private static Optional<Descriptor> fromMethod(MethodDeclaration method) {
        if (!DESCRIPTOR.equals(method.getType().asString())) {
            return Optional.empty();
        }
        Optional<ObjectCreationExpr> construction = method.findAll(ReturnStmt.class).stream()
                .flatMap(ret -> ret.getExpression().stream())
                .flatMap(expr -> expr.toObjectCreationExpr().stream())
                .filter(created -> DESCRIPTOR.equals(created.getType().getNameAsString()))
                .findFirst();
        if (construction.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Expression> locals = localsOf(method);

        String stream = firstLiteral(construction.get(), locals);
        if (stream == null) {
            // Without the stream's own name there is nothing to declare a contract ABOUT: a body
            // keyed on the wrong stream is worse than an absent one.
            return Optional.empty();
        }

        List<String> key = new ArrayList<>();
        List<String> channels = new ArrayList<>();
        for (Expression argument : construction.get().getArguments()) {
            Expression resolved = resolve(argument, locals);
            if (isCreationOf(resolved, KEY_SPEC)) {
                key.addAll(keyFieldsOf(resolved.asObjectCreationExpr(), locals));
            } else if (isListOfCreations(resolved, locals, CHANNEL_BINDING)) {
                for (Expression element : listElements(resolved)) {
                    channels.add(frameTypeOf(resolve(element, locals).asObjectCreationExpr(), locals));
                }
            }
        }
        return Optional.of(new Descriptor(stream, key, channels));
    }

    /** Field names in declaration order, from the {@code List.of(new KeyField(…), …)} argument. */
    private static List<String> keyFieldsOf(ObjectCreationExpr keySpec, Map<String, Expression> locals) {
        List<String> names = new ArrayList<>();
        for (Expression argument : keySpec.getArguments()) {
            Expression resolved = resolve(argument, locals);
            if (!isListOfCreations(resolved, locals, KEY_FIELD)) {
                continue;
            }
            for (Expression element : listElements(resolved)) {
                String name = firstLiteral(resolve(element, locals).asObjectCreationExpr(), locals);
                if (name != null) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    /** The channel's frame type, or null when it is not a literal — which is what a channel
     *  discriminated by a payload field looks like, and is a fact about the descriptor rather than
     *  a failure to read it. */
    private static String frameTypeOf(ObjectCreationExpr channel, Map<String, Expression> locals) {
        for (Expression argument : channel.getArguments()) {
            Expression resolved = resolve(argument, locals);
            if (isCreationOf(resolved, FRAME_TYPE)) {
                return firstLiteral(resolved.asObjectCreationExpr(), locals);
            }
        }
        return null;
    }

    /**
     * The FIRST argument, when it is a string literal, and null otherwise.
     *
     * <p>Strictly the first — not the first argument that happens to be a literal. Which argument
     * holds a nested part is found by type above, because those types are distinct; WITHIN a part
     * the strings are not distinguishable by type and only position separates them.
     * {@code new FrameType(null, "kind", …)} is the case that decides this: scanning for the first
     * literal reads the discriminator FIELD NAME and records it as a frame type, inventing a
     * channel that does not exist. The honest answer there is that this channel has no single
     * frame type, which is what null says.
     */
    private static String firstLiteral(ObjectCreationExpr creation, Map<String, Expression> locals) {
        if (creation.getArguments().isEmpty()) {
            return null;
        }
        Expression first = resolve(creation.getArgument(0), locals);
        return first.isStringLiteralExpr() ? first.asStringLiteralExpr().asString() : null;
    }

    /** Local variable name to its initializer, so a part assembled above the return statement can
     *  be followed to where it was built. Declaration order, so a later reassignment wins — the
     *  same first-write-then-refine shape a builder method uses. */
    private static Map<String, Expression> localsOf(MethodDeclaration method) {
        Map<String, Expression> locals = new LinkedHashMap<>();
        method.findAll(VariableDeclarator.class).forEach(declarator ->
                declarator.getInitializer().ifPresent(init ->
                        locals.put(declarator.getNameAsString(), init)));
        return locals;
    }

    /** Follows a bare name to what it was initialized with, once. A builder assembles its parts in
     *  one step each, so a deeper walk would buy nothing and could not terminate on a self
     *  reference. */
    private static Expression resolve(Expression expression, Map<String, Expression> locals) {
        if (expression.isNameExpr()) {
            Expression initializer = locals.get(expression.asNameExpr().getNameAsString());
            if (initializer != null && !(initializer instanceof NameExpr)) {
                return initializer;
            }
        }
        return expression;
    }

    private static boolean isCreationOf(Expression expression, String simpleName) {
        return expression.isObjectCreationExpr()
                && simpleName.equals(expression.asObjectCreationExpr().getType().getNameAsString());
    }

    private static boolean isListOfCreations(Expression expression, Map<String, Expression> locals,
                                             String simpleName) {
        if (!isListOf(expression)) {
            return false;
        }
        List<Expression> elements = listElements(expression);
        return !elements.isEmpty() && elements.stream()
                .allMatch(element -> isCreationOf(resolve(element, locals), simpleName));
    }

    private static boolean isListOf(Expression expression) {
        return expression.isMethodCallExpr()
                && "of".equals(expression.asMethodCallExpr().getNameAsString())
                && expression.asMethodCallExpr().getScope()
                .map(scope -> scope.isNameExpr() && "List".equals(scope.asNameExpr().getNameAsString()))
                .orElse(false);
    }

    private static List<Expression> listElements(Expression expression) {
        MethodCallExpr call = expression.asMethodCallExpr();
        return new ArrayList<>(call.getArguments());
    }
}
