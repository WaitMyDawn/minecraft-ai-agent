package yagen.waitmydawn.maa.model;

import java.util.List;

/** 目标环境的版本可用性证据；不代表依赖闭包完整或可以启动。 */
public record RequiredModsReport(String loader, String mcVersion, List<Entry> entries) {
    public enum Availability { AVAILABLE, NO_COMPATIBLE_VERSION, UNVERIFIED }
    public record Entry(String slug, Availability availability) {}
    public RequiredModsReport { entries = List.copyOf(entries); }
    public String status() { return entries.stream().allMatch(e -> e.availability() == Availability.AVAILABLE) ? "READY" : "BLOCKED"; }
    public List<String> unavailable() { return entries.stream().filter(e -> e.availability() != Availability.AVAILABLE).map(Entry::slug).toList(); }
}
