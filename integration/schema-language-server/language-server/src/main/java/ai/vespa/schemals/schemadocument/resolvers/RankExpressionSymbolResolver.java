package ai.vespa.schemals.schemadocument.resolvers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;

import com.google.protobuf.Option;

import ai.vespa.schemals.index.Symbol;
import ai.vespa.schemals.index.Symbol.SymbolStatus;
import ai.vespa.schemals.index.Symbol.SymbolType;
import ai.vespa.schemals.parser.rankingexpression.ast.args;
import ai.vespa.schemals.parser.rankingexpression.ast.expression;
import ai.vespa.schemals.parser.rankingexpression.ast.unaryFunctionName;
import ai.vespa.schemals.context.ParseContext;
import ai.vespa.schemals.tree.SchemaNode;
import ai.vespa.schemals.tree.SchemaNode.LanguageType;

public class RankExpressionSymbolResolver {
    

    public static final Map<String, SymbolType> rankExpressionBultInFunctions = new HashMap<>() {{
        put("bm25", SymbolType.FIELD);
        put("attribute", SymbolType.FIELD);
        put("query", SymbolType.QUERY_INPUT);
        put("distance", SymbolType.TYPE_UNKNOWN);
    }};

    /**
     * Resolves rank expression references in the tree
     *
     * @param node        The schema node to resolve the rank expression references in.
     * @param context     The parse context.
     */
    public static List<Diagnostic> resolveRankExpressionReferences(SchemaNode node, ParseContext context) {
        List<Diagnostic> diagnostics = new ArrayList<>();

        if (
            node.getLanguageType() == LanguageType.RANK_EXPRESSION &&
            node.hasSymbol()
        ) {
            if (node.getSymbol().getStatus() == SymbolStatus.UNRESOLVED) {
                resolveReference(node.getSymbol(), context, diagnostics);
            }

            if (node.getSymbol().getStatus() == SymbolStatus.UNRESOLVED) {
                findSymbolTypeOfBuiltInArgument(node, context, diagnostics);
            }

            // if (node.getSymbol().getStatus() == SymbolStatus.UNRESOLVED) {
            //     node.setSymbolStatus(SymbolStatus.REFERENCE); // TODO: remove later, is placed here to pass tests
            // }

        }

        for (SchemaNode child : node) {
            diagnostics.addAll(resolveRankExpressionReferences(child, context));
        }

        return diagnostics;
    }

    private static List<SchemaNode> findRankExpressionArguments(SchemaNode node) {
        List<SchemaNode> ret = new ArrayList<>();

        SchemaNode parent = node.getParent();

        if (parent == null || parent.size() < 3) return ret;

        SchemaNode argNode = parent.get(2);
        if (!argNode.isASTInstance(args.class)) return ret;

        for (SchemaNode child : argNode) {
            if (child.isASTInstance(expression.class)) {
                ret.add(child);
            }
        }

        return ret;
    }

    public static final Set<Class<?>> builInTokenizedFunctions = new HashSet<>() {{
        add(unaryFunctionName.class);
    }};

    private static void findSymbolTypeOfBuiltInArgument(SchemaNode node, ParseContext context, List<Diagnostic> diagnostics) {

        boolean isBuiltInTokenizedFunction = builInTokenizedFunctions.contains(node.getASTClass());
        if (isBuiltInTokenizedFunction) {
            Symbol symbol = node.getSymbol();
            symbol.setType(SymbolType.FUNCTION);
            symbol.setStatus(SymbolStatus.BUILTIN_REFERENCE);
            return;
        }

        String identifier = node.getSymbol().getShortIdentifier();

        SymbolType arguemntType = rankExpressionBultInFunctions.get(identifier);
        if (arguemntType == null) return;
        node.getSymbol().setType(SymbolType.FUNCTION);
        node.setSymbolStatus(SymbolStatus.BUILTIN_REFERENCE);

        List<SchemaNode> arguments = findRankExpressionArguments(node);

        if (identifier.equals("distance")) {
            resolveDistanceFunction(node, arguments, diagnostics);
            return;
        }

        for (SchemaNode arg : arguments) {
            SchemaNode symbolNode = arg;

            while (!symbolNode.hasSymbol() && symbolNode.size() > 0) {
                symbolNode = symbolNode.get(0);
            }

            if (symbolNode.hasSymbol()) {
                Symbol symbol = symbolNode.getSymbol();
                context.logger().println(symbol);
                if (symbol.getStatus() == SymbolStatus.UNRESOLVED && symbol.getType() == SymbolType.TYPE_UNKNOWN) {
                    symbol.setType(arguemntType);
                }
            }
        }
    }

    private static void resolveDistanceFunction(SchemaNode node, List<SchemaNode> argument, List<Diagnostic> diagnostics) {

        if (argument.size() != 2) {
            diagnostics.add(new Diagnostic(node.getRange(), "The distance function takes two argument (dimension, name)", DiagnosticSeverity.Error, ""));
            return;
        }

        SchemaNode firstArgument = argument.get(0);
        while (!firstArgument.hasSymbol() && firstArgument.size() > 0) {
            firstArgument = firstArgument.get(0);
        }

        if (firstArgument.hasSymbol()) {
            firstArgument.removeSymbol();
        }

        boolean isField = firstArgument.getText().equals("field");
        boolean isLabel = firstArgument.getText().equals("label");

        if (!isField && !isLabel) {
            diagnostics.add(new Diagnostic(firstArgument.getRange(), "The first argument must be field or label", DiagnosticSeverity.Error, ""));
            return;
        }

        SchemaNode secondArgument = argument.get(1);
        while (!secondArgument.hasSymbol() && secondArgument.size() > 0) {
            secondArgument = secondArgument.get(0);
        }

        if (!secondArgument.hasSymbol() && isField) {
            return;
        }

        SymbolType newType = isField ? SymbolType.FIELD : SymbolType.LABEL;
        secondArgument.setSymbolType(newType);

        if (isLabel) {
            secondArgument.setSymbolStatus(SymbolStatus.BUILTIN_REFERENCE);
        }
    }

    private static final List<SymbolType> possibleTypes = new ArrayList<>() {{
        add(SymbolType.FUNCTION);
        add(SymbolType.PARAMETER);
    }};

    private static void resolveReference(Symbol reference, ParseContext context, List<Diagnostic> diagnostics) {
        List<Symbol> possibleDefinitions = new ArrayList<>();

        for (SymbolType possibleType : possibleTypes) {
            Optional<Symbol> symbol = context.schemaIndex().findSymbol(reference.getScope(), possibleType, reference.getShortIdentifier());
            if (symbol.isPresent()) {
                possibleDefinitions.add(symbol.get());
            }
        }

        if (possibleDefinitions.size() == 0) {
            return;
        }

        // TODO: filter for away the definitions with large scope

        if (possibleDefinitions.size() > 1) {
            diagnostics.add(new Diagnostic(reference.getLocation().getRange(), "The reference is ambiguous.", DiagnosticSeverity.Error, ""));
            return;
        }

        Symbol definition = possibleDefinitions.get(0);

        reference.setType(definition.getType());
        reference.setStatus(SymbolStatus.REFERENCE);
        context.schemaIndex().insertSymbolReference(definition, reference);
    }
}
