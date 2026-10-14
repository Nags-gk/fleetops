package dev.nagsgk.fleetops.triage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Retrieval for the on-call assistant: TF-IDF cosine similarity over runbook sections.
 * Small, dependency-free and deterministic, which suits a Lambda cold start better than an
 * embedding service for a corpus of a few dozen sections.
 */
public final class RunbookIndex {
    public record Section(String title, String text) {}

    public record Hit(Section section, double score) {}

    private final List<Section> sections = new ArrayList<>();
    private final List<Map<String, Double>> vectors = new ArrayList<>();
    private final Map<String, Double> idf = new HashMap<>();

    public static RunbookIndex fromClasspath(String... resources) {
        var text = new StringBuilder();
        for (String r : resources) {
            try (InputStream in = RunbookIndex.class.getResourceAsStream(r)) {
                if (in == null) throw new IllegalStateException("missing runbook " + r);
                text.append(new String(in.readAllBytes(), StandardCharsets.UTF_8)).append('\n');
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        return new RunbookIndex(text.toString());
    }

    public RunbookIndex(String markdown) {
        String title = null;
        var buf = new StringBuilder();
        for (String line : markdown.split("\n")) {
            if (line.startsWith("## ")) {
                if (title != null) sections.add(new Section(title, buf.toString().strip()));
                title = line.substring(3).strip();
                buf.setLength(0);
            } else if (title != null && !line.startsWith("# ")) {
                buf.append(line).append('\n');
            }
        }
        if (title != null) sections.add(new Section(title, buf.toString().strip()));
        var df = new HashMap<String, Integer>();
        var tfs = new ArrayList<Map<String, Integer>>();
        for (var s : sections) {
            var tf = termFreq(s.title() + " " + s.title() + " " + s.text());
            tfs.add(tf);
            tf.keySet().forEach(t -> df.merge(t, 1, Integer::sum));
        }
        int n = sections.size();
        df.forEach((t, d) -> idf.put(t, Math.log(1.0 + (double) n / d)));
        for (var tf : tfs) vectors.add(normalise(weight(tf)));
    }

    static List<String> tokens(String s) {
        var out = new ArrayList<String>();
        for (String t : s.toLowerCase(Locale.ROOT).split("[^a-z0-9_]+")) {
            if (t.length() > 1) {
                out.add(t);
                for (String part : t.split("_")) if (part.length() > 2 && !part.equals(t)) out.add(part);
            }
        }
        return out;
    }

    private static Map<String, Integer> termFreq(String s) {
        var m = new HashMap<String, Integer>();
        tokens(s).forEach(t -> m.merge(t, 1, Integer::sum));
        return m;
    }

    private Map<String, Double> weight(Map<String, Integer> tf) {
        var v = new HashMap<String, Double>();
        tf.forEach((t, c) -> v.put(t, (1 + Math.log(c)) * idf.getOrDefault(t, 0.0)));
        return v;
    }

    private static Map<String, Double> normalise(Map<String, Double> v) {
        double norm = Math.sqrt(v.values().stream().mapToDouble(x -> x * x).sum());
        if (norm == 0) return v;
        var o = new HashMap<String, Double>();
        v.forEach((k, x) -> o.put(k, x / norm));
        return o;
    }

    public List<Hit> search(String query, int k) {
        var q = normalise(weight(termFreq(query)));
        var hits = new ArrayList<Hit>();
        for (int i = 0; i < sections.size(); i++) {
            double dot = 0;
            for (var e : q.entrySet()) dot += e.getValue() * vectors.get(i).getOrDefault(e.getKey(), 0.0);
            if (dot > 0) hits.add(new Hit(sections.get(i), dot));
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed());
        return hits.subList(0, Math.min(k, hits.size()));
    }

    public int size() {
        return sections.size();
    }
}
