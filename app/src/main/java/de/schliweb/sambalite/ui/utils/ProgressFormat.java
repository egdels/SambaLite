package de.schliweb.sambalite.ui.utils;

import de.schliweb.sambalite.util.EnhancedFileUtils;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ProgressFormat {

    private ProgressFormat() {
    }

    public enum Op {
        DOWNLOAD("Downloading"),
        UPLOAD("Uploading"),
        DELETE("Deleting"),
        RENAME("Renaming"),
        SEARCH("Searching"),
        FINALIZING("Finalizing"),
        UNKNOWN("Processing");

        private final String label;

        Op(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public static Op fromString(String s) {
            if (s == null) return UNKNOWN;
            String x = s.toLowerCase(Locale.US);
            if (x.startsWith("download")) return DOWNLOAD;
            if (x.startsWith("upload")) return UPLOAD;
            if (x.startsWith("delet")) return DELETE;
            if (x.startsWith("renam")) return RENAME;
            if (x.startsWith("search")) return SEARCH;
            if (x.startsWith("final")) return FINALIZING;
            return UNKNOWN;
        }
    }

    public static final class Result {
        private final Op op;
        private final int overallPct;
        private final Integer cur;
        private final Integer total;
        private final Integer filePct;
        private final String fileName;
        private final String rawFileName;

        Result(Op op, int overallPct, Integer cur, Integer total,
               Integer filePct, String fileName, String rawFileName) {
            this.op = op;
            this.overallPct = overallPct;
            this.cur = cur;
            this.total = total;
            this.filePct = filePct;
            this.fileName = fileName;
            this.rawFileName = rawFileName;
        }

        public Op op() {
            return op;
        }

        public int overallPct() {
            return overallPct;
        }

        public Optional<Integer> cur() {
            return Optional.ofNullable(cur);
        }

        public Optional<Integer> total() {
            return Optional.ofNullable(total);
        }

        public Optional<Integer> filePct() {
            return Optional.ofNullable(filePct);
        }

        public Optional<String> fileName() {
            return Optional.ofNullable(fileName);
        }

        public Optional<String> rawFileName() {
            return Optional.ofNullable(rawFileName);
        }
    }

    public static Result parse(String raw, int overallFallback, String fallbackName) {
        String s = raw != null ? raw.trim() : "";
        int overall = clampPct(overallFallback);

        Op op = Op.UNKNOWN;
        String body = s;
        Matcher mp = P_PREFIX.matcher(s);
        if (mp.find()) {
            op = Op.fromString(mp.group(1));
            body = mp.group(2).trim();
        }

        Integer cur = null, total = null, filePct = null;
        String foundName = null;

        Matcher mb = P_BRACKET.matcher(s);
        if (mb.find()) {
            try {
                overall = clampPct(Integer.parseInt(mb.group(1)));
            } catch (Exception ignore) {
            }
            try {
                cur = Integer.parseInt(mb.group(2));
            } catch (Exception ignore) {
            }
            try {
                total = Integer.parseInt(mb.group(3));
            } catch (Exception ignore) {
            }
            String tail = mb.group(4) != null ? mb.group(4).trim() : "";
            foundName = stripBullet(tail);
        } else {
            String main = body;
            String details = null;
            int bullet = indexBullet(body);
            if (bullet >= 0) {
                main = body.substring(0, bullet).trim();
                details = body.substring(bullet + 1).trim(); // ohne "•"
                Integer fp = parseLeadingPercent(details);
                if (fp != null) filePct = clampPct(fp);
            }

            Matcher mPctIdx = P_PCT_IDX_NAME.matcher(main);
            if (mPctIdx.find()) {
                try {
                    overall = clampPct(Integer.parseInt(mPctIdx.group(1)));
                } catch (Exception ignore) {
                }
                try {
                    cur = Integer.parseInt(mPctIdx.group(2));
                } catch (Exception ignore) {
                }
                try {
                    total = Integer.parseInt(mPctIdx.group(3));
                } catch (Exception ignore) {
                }
                foundName = mPctIdx.group(4) != null ? stripBullet(mPctIdx.group(4).trim()) : null;
            } else {
                Matcher mIdx = P_IDX_NAME.matcher(main);
                if (mIdx.find()) {
                    try {
                        cur = Integer.parseInt(mIdx.group(1));
                    } catch (Exception ignore) {
                    }
                    try {
                        total = Integer.parseInt(mIdx.group(2));
                    } catch (Exception ignore) {
                    }
                    foundName = mIdx.group(3) != null ? stripBullet(mIdx.group(3).trim()) : null;
                } else {
                    Matcher mPctName = P_PCT_NAME.matcher(main);
                    if (mPctName.find()) {
                        try {
                            overall = clampPct(Integer.parseInt(mPctName.group(1)));
                        } catch (Exception ignore) {
                        }
                        foundName = mPctName.group(2) != null ? stripBullet(mPctName.group(2).trim()) : null;
                    } else {
                        Matcher mBytes = P_BYTES_ONLY.matcher(main);
                        if (mBytes.find()) {
                            foundName = null;
                        } else {

                            if (!main.isEmpty() && !looksLikeProgress(main)) {
                                foundName = stripBullet(main);
                            }
                        }
                    }
                }
            }
        }

        String base = baseNameOrNull(foundName);
        String baseFallback = baseNameOrNull(fallbackName);

        String finalName =
                base != null && !base.isEmpty() ? base :
                        baseFallback;

        return new Result(op, overall, cur, total, filePct, finalName, foundName);
    }

    public static String buildUnified(Op op, int currentFile, int totalFiles, String fileName) {
        String safeName = fileName != null ? fileName : "";
        return op.label() + ": " + currentFile + "/" + totalFiles + " - " + safeName;
    }

    public static String toHeadlineNoPercent(Result r) {
        if (r == null) return "";
        if (r.op() == Op.FINALIZING) return r.op().label() + "…";
        if (r.cur().isPresent() && r.total().isPresent()) {
            return r.op().label() + ": " + r.cur().get() + "/" + r.total().get();
        }
        if (r.op() != Op.UNKNOWN) return r.op().label() + "…";
        return "";
    }


    public static String toDetailsFilename(Result r, int maxLen) {
        if (r == null) return "";
        String name = r.fileName().orElse("");
        if (name.length() > Math.max(10, maxLen)) {
            return name.substring(0, maxLen - 1) + "…";
        }
        return name;
    }

    private static final Pattern P_PREFIX = Pattern.compile("^\\s*(Downloading|Uploading|Deleting|Renaming|Searching|Finalizing|Copying)[:\\s]+(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_BRACKET = Pattern.compile("^\\s*\\[PROGRESS:(\\d{1,3}):(\\d+):(\\d+)\\](.*)$");
    private static final Pattern P_PCT_IDX_NAME = Pattern.compile("^(\\d{1,3})%\\s*\\((\\d+)\\s*/\\s*(\\d+)\\)\\s*-\\s*(.+)$");
    private static final Pattern P_IDX_NAME = Pattern.compile("^(\\d+)\\s*/\\s*(\\d+)\\s*-\\s*(.+)$");
    private static final Pattern P_PCT_NAME = Pattern.compile("^(\\d{1,3})%\\s*-\\s*(.+)$");
    private static final Pattern P_BYTES_ONLY = Pattern.compile("^\\s*([0-9]+(?:\\.[0-9])?\\s*[KMGTPE]?B)\\s*/\\s*([0-9]+(?:\\.[0-9])?\\s*[KMGTPE]?B)\\s*$", Pattern.CASE_INSENSITIVE);

    private static final Pattern P_ANY_PERCENT =
            Pattern.compile("(\\d{1,3})%");


    private static int clampPct(int p) {
        return Math.max(0, Math.min(100, p));
    }

    private static String baseNameOrNull(String path) {
        if (path == null) return null;
        String s = path.trim();
        if (s.isEmpty()) return null;
        int bullet = indexBullet(s);
        if (bullet >= 0) s = s.substring(0, bullet).trim();
        s = s.replace('\\', '/');
        int slash = s.lastIndexOf('/');
        return slash >= 0 ? s.substring(slash + 1) : s;
    }

    private static String stripBullet(String s) {
        if (s == null) return null;
        int bullet = indexBullet(s);
        return bullet >= 0 ? s.substring(0, bullet).trim() : s.trim();
    }

    private static int indexBullet(String s) {
        int p = s.indexOf('•');
        if (p >= 0) return p;
        return s.indexOf(" • ");
    }

    private static boolean looksLikeProgress(String s) {
        return P_PCT_IDX_NAME.matcher(s).find()
                || P_IDX_NAME.matcher(s).find()
                || P_PCT_NAME.matcher(s).find()
                || P_BYTES_ONLY.matcher(s).find()
                || P_BRACKET.matcher(s).find();
    }

    private static Integer parseLeadingPercent(String s) {
        if (s == null) return null;
        Matcher m = Pattern.compile("^(\\d{1,3})%").matcher(s.trim());
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (Exception ignore) {
            }
        }
        return null;
    }

    public static String formatIdx(String op, int cur, int total, String fileName) {
        String base = fileName == null ? "" : fileName;
        base = base.replace('\\', '/');
        int s = base.lastIndexOf('/');
        if (s >= 0) base = base.substring(s + 1);
        return op + ": " + cur + "/" + total + " - " + base;
    }


    public static int parsePercent(String text) {
        if (text == null) return 0;
        String s = text.trim();
        if (s.isEmpty()) return 0;

        Matcher mb = P_BRACKET.matcher(s);
        if (mb.find()) {
            try {
                return clampPct(Integer.parseInt(mb.group(1)));
            } catch (Exception ignore) {
            }
        }

        Matcher m = P_ANY_PERCENT.matcher(s);
        if (m.find()) {
            try {
                return clampPct(Integer.parseInt(m.group(1)));
            } catch (Exception ignore) {
            }
        }

        return 0;
    }


    public static String formatBytesOnly(long currentBytes, long totalBytes) {
        return EnhancedFileUtils.formatFileSize(currentBytes) + " / " +
                EnhancedFileUtils.formatFileSize(totalBytes);
    }

    public static String formatBytes(String verb, long currentBytes, long totalBytes) {
        return verb + ": " + formatBytesOnly(currentBytes, totalBytes);
    }

    public static String normalizePercentInStatus(String status, int pct) {
        if (status == null) return "";
        return status.replaceAll("\\b(\\d{1,3})%\\b", Math.max(0, Math.min(100, pct)) + "%");
    }

    public static int percentOfBytes(long current, long total) {
        if (total <= 0) return 0;
        if (current >= total || total - current <= 1024) return 100;
        return (int) Math.round((current * 100.0) / total);
    }
}
