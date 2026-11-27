package edu.hust.crawler;

import java.util.*;
import java.util.regex.Pattern;

/** Longest matching rule wins; Allow wins ties. Specific agent groups override '*'. */
final class RobotsRules {
    private record Rule(String path, boolean allow) {}
    private final List<Rule> rules;
    private final long crawlDelayMillis;
    private RobotsRules(List<Rule> rules, long delay) { this.rules = rules; this.crawlDelayMillis = delay; }
    long crawlDelayMillis() { return crawlDelayMillis; }
    static RobotsRules parse(String text) {
        List<Rule> wildcard = new ArrayList<>(), specific = new ArrayList<>();
        List<String> agents = new ArrayList<>();
        boolean hasRules = false, hasSpecific = false;
        long wildcardDelay = 0, specificDelay = 0;
        for (String line : text.split("\\R")) {
            line = line.split("#", 2)[0].trim();
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (key.equals("user-agent")) {
                if (hasRules) { agents.clear(); hasRules = false; }
                agents.add(value.toLowerCase(Locale.ROOT));
                if (value.equalsIgnoreCase("HustResearchCrawler")) hasSpecific = true;
            } else {
                hasRules = true;
                if (key.equals("crawl-delay")) {
                    try {
                        double seconds = Double.parseDouble(value);
                        if (Double.isFinite(seconds) && seconds >= 0 && seconds <= 86400) {
                            long millis = (long) Math.ceil(seconds * 1000);
                            if (agents.contains("hustresearchcrawler")) specificDelay = Math.max(specificDelay, millis);
                            if (agents.contains("*")) wildcardDelay = Math.max(wildcardDelay, millis);
                        }
                    } catch (NumberFormatException ignored) { }
                    continue;
                }
                if (!(key.equals("allow") || key.equals("disallow"))) continue;
                if (value.isEmpty()) continue;
                Rule rule = new Rule(value, key.equals("allow"));
                if (agents.contains("hustresearchcrawler")) specific.add(rule);
                if (agents.contains("*")) wildcard.add(rule);
            }
        }
        return new RobotsRules(hasSpecific ? specific : wildcard, hasSpecific ? specificDelay : wildcardDelay);
    }
    boolean allowed(String path) {
        int best = -1; boolean allow = true;
        for (Rule r : rules) {
            String raw = r.path();
            boolean end = raw.endsWith("$");
            if (end) raw = raw.substring(0, raw.length() - 1);
            String regex = "^" + String.join(".*", Arrays.stream(raw.split("\\*", -1)).map(Pattern::quote).toList()) + (end ? "$" : "");
            int length = raw.replace("*", "").length();
            if (Pattern.compile(regex).matcher(path).find() && (length > best || length == best && r.allow())) {
                best = length; allow = r.allow();
            }
        }
        return allow;
    }
}
