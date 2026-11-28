package com.example.sqlagent.attachment.document;

import com.example.sqlagent.attachment.AttachmentProperties;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class DocumentContextBuilder {
    private static final Pattern HAN_RUN = Pattern.compile("[\\p{IsHan}]+");
    private static final Pattern WORD_RUN = Pattern.compile("[a-z0-9_]{2,}");
    private static final Pattern PAGE_REFERENCE =
            Pattern.compile("第\\s*(\\d+)\\s*页");

    private static final Set<String> STOP_WORDS = Set.of(
            "这个", "这份", "一个", "如何", "什么", "哪些", "查询",
            "请问", "请帮", "帮助", "一下", "文档", "附件", "文件",
            "内容", "里面", "相关", "需要", "根据", "给出", "说明",
            "分析", "告诉", "以及", "中的", "可以", "是否"
    );

    private final AttachmentProperties properties;

    public DocumentContextBuilder(AttachmentProperties properties) {
        this.properties = properties;
    }

    public String build(String userQuery, List<DocumentSource> documents) {
        if (documents == null || documents.isEmpty()) {
            return "";
        }

        int totalBudget = Math.max(2_000, properties.getMaxDocumentContextChars());
        int perDocumentBudget = Math.max(
                1_000,
                properties.getMaxContextCharsPerDocument()
        );
        int maxUnits = Math.max(1, properties.getMaxContextUnitsPerDocument());
        Set<String> queryTokens = tokens(userQuery);
        Integer requestedPage = requestedPage(userQuery);
        boolean overviewRequest = isOverviewRequest(userQuery);

        StringBuilder context = new StringBuilder();
        context.append("<document_context>\n")
                .append("以下内容由附件解析产物按当前问题筛选，仅包含必要片段。\n");

        for (DocumentSource source : documents) {
            int remaining = totalBudget - context.length();
            if (remaining < 500) {
                break;
            }

            int documentStart = context.length();
            ParsedDocument document = source.document();
            int documentBudget = Math.min(
                    perDocumentBudget,
                    remaining - 250
            );
            String manifest = Objects.requireNonNullElse(
                    document.manifest().summary(),
                    "无文档清单"
            );

            context.append("\n<document name=\"")
                    .append(escapeAttribute(source.name()))
                    .append("\" format=\"")
                    .append(document.manifest().format())
                    .append("\">\n")
                    .append("清单：")
                    .append(take(manifest, Math.min(1_200, documentBudget / 3)))
                    .append('\n');

            List<ScoredUnit> selected = selectUnits(
                    document.units(),
                    queryTokens,
                    requestedPage,
                    overviewRequest,
                    overviewRequest
                            ? Math.min(12, Math.max(maxUnits, document.units().size()))
                            : maxUnits
            );

            if (selected.isEmpty()) {
                context.append("未提取到可用的正文片段。\n");
            } else {
                int usedInDocument = context.length() - documentStart;
                int unitBudget = Math.max(500, documentBudget - usedInDocument);
                int used = 0;
                for (ScoredUnit scored : selected) {
                    if (used >= unitBudget) {
                        break;
                    }
                    DocumentUnit unit = scored.unit();
                    String block = "\n[%s]\n%s\n".formatted(
                            unit.locator(),
                            unit.text()
                    );
                    block = take(block, unitBudget - used);
                    context.append(block);
                    used += block.length();
                }
                context.append("选择依据：")
                        .append(selected.stream()
                                .map(item -> item.unit().locator())
                                .reduce((left, right) -> left + "、" + right)
                                .orElse("无"))
                        .append('\n');
            }

            context.append("</document>\n");
        }

        context.append("</document_context>");
        return take(context.toString(), totalBudget);
    }

    private List<ScoredUnit> selectUnits(
            List<DocumentUnit> units,
            Set<String> queryTokens,
            Integer requestedPage,
            boolean overviewRequest,
            int maxUnits
    ) {
        if (units == null || units.isEmpty()) {
            return List.of();
        }

        if (overviewRequest) {
            return sampleUnits(units, maxUnits);
        }

        List<ScoredUnit> scored = new ArrayList<>();
        for (int index = 0; index < units.size(); index++) {
            DocumentUnit unit = units.get(index);
            int score = score(unit, queryTokens, requestedPage);
            scored.add(new ScoredUnit(unit, score, index));
        }

        List<ScoredUnit> selected = scored.stream()
                .filter(item -> item.score() > 0)
                .sorted(Comparator
                        .comparingInt(ScoredUnit::score)
                        .reversed()
                        .thenComparingInt(ScoredUnit::index))
                .limit(maxUnits)
                .toList();

        if (!selected.isEmpty()) {
            return selected;
        }
        return units.stream()
                .limit(maxUnits)
                .map(unit -> new ScoredUnit(unit, 0, units.indexOf(unit)))
                .toList();
    }

    private List<ScoredUnit> sampleUnits(List<DocumentUnit> units, int maxUnits) {
        if (units.size() <= maxUnits) {
            return units.stream()
                    .map(unit -> new ScoredUnit(unit, 0, units.indexOf(unit)))
                    .toList();
        }
        if (maxUnits == 1) {
            return List.of(new ScoredUnit(units.get(0), 0, 0));
        }

        List<ScoredUnit> sampled = new ArrayList<>(maxUnits);
        for (int index = 0; index < maxUnits; index++) {
            int unitIndex = Math.min(
                    units.size() - 1,
                    (int) Math.round(
                            index * (units.size() - 1.0) / (maxUnits - 1.0)
                    )
            );
            sampled.add(new ScoredUnit(units.get(unitIndex), 0, unitIndex));
        }
        return sampled;
    }

    private int score(
            DocumentUnit unit,
            Set<String> queryTokens,
            Integer requestedPage
    ) {
        String searchable = (
                Objects.requireNonNullElse(unit.locator(), "")
                        + "\n"
                        + Objects.requireNonNullElse(unit.title(), "")
                        + "\n"
                        + Objects.requireNonNullElse(unit.text(), "")
        ).toLowerCase(Locale.ROOT);

        int score = 0;
        for (String token : queryTokens) {
            if (searchable.contains(token)) {
                score += Math.min(8, countOccurrences(searchable, token)) * 3;
                if (Objects.requireNonNullElse(unit.title(), "")
                        .toLowerCase(Locale.ROOT)
                        .contains(token)) {
                    score += 12;
                }
                if (Objects.requireNonNullElse(unit.locator(), "")
                        .toLowerCase(Locale.ROOT)
                        .contains(token)) {
                    score += 10;
                }
            }
        }

        if (requestedPage != null
                && String.valueOf(requestedPage).equals(unit.metadata().get("page"))) {
            score += 100;
        }

        String sheet = unit.metadata().get("sheet");
        if (sheet != null && !sheet.isBlank()) {
            String lowerSheet = sheet.toLowerCase(Locale.ROOT);
            if (queryTokens.stream().anyMatch(lowerSheet::contains)) {
                score += 50;
            }
        }
        return score;
    }

    private Set<String> tokens(String query) {
        Set<String> tokens = new LinkedHashSet<>();
        String normalized = Objects.requireNonNullElse(query, "")
                .toLowerCase(Locale.ROOT);

        Matcher han = HAN_RUN.matcher(normalized);
        while (han.find()) {
            String run = han.group();
            if (run.length() <= 6 && !STOP_WORDS.contains(run)) {
                tokens.add(run);
            }
            for (int index = 0; index + 2 <= run.length(); index++) {
                String token = run.substring(index, index + 2);
                if (!STOP_WORDS.contains(token)) {
                    tokens.add(token);
                }
            }
        }

        Matcher words = WORD_RUN.matcher(normalized);
        while (words.find()) {
            tokens.add(words.group());
        }
        return tokens;
    }

    private Integer requestedPage(String query) {
        Matcher matcher = PAGE_REFERENCE.matcher(
                Objects.requireNonNullElse(query, "")
        );
        if (!matcher.find()) {
            return null;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean isOverviewRequest(String query) {
        String value = Objects.requireNonNullElse(query, "");
        return value.contains("总结")
                || value.contains("概括")
                || value.contains("概述")
                || value.contains("主要内容")
                || value.contains("整份")
                || value.contains("全文")
                || value.contains("目录");
    }

    private int countOccurrences(String text, String token) {
        int count = 0;
        int index = 0;
        while (count < 8 && (index = text.indexOf(token, index)) >= 0) {
            count++;
            index += Math.max(1, token.length());
        }
        return count;
    }

    private String take(String text, int maxChars) {
        if (maxChars <= 0) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars)
                + (maxChars > 20 ? "\n[上下文已截断]" : "");
    }

    private String escapeAttribute(String value) {
        return Objects.requireNonNullElse(value, "document")
                .replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    public record DocumentSource(String name, ParsedDocument document) {
    }

    private record ScoredUnit(DocumentUnit unit, int score, int index) {
    }
}
