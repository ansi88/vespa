package ai.vespa.schemals.completion.utils;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.InsertTextFormat;

public class CompletionUtils {
    public static CompletionItem constructSnippet(String label, String snippet, String detail) {
        CompletionItem item = new CompletionItem();
        item.setLabel(label);
        item.setKind(CompletionItemKind.Snippet);
        item.setInsertTextFormat(InsertTextFormat.Snippet);
        item.setInsertText(snippet);
        item.setDetail(detail);
        return item;
    }

    public static CompletionItem constructSnippet(String label, String snippet) {
        return constructSnippet(label, snippet, "");
    }

    public static CompletionItem constructType(String label) {
        CompletionItem item = new CompletionItem();
        item.setLabel(label);
        item.setKind(CompletionItemKind.TypeParameter);
        return item;
    }
}
