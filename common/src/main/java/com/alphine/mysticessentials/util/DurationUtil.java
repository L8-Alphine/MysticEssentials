package com.alphine.mysticessentials.util;

import java.util.regex.*;
public final class DurationUtil {
    private DurationUtil(){}
    private static final Pattern P = Pattern.compile("(\\d+)([smhdw])", Pattern.CASE_INSENSITIVE);
    public static long parseToMillis(String s) {
        if (s == null || s.isBlank()) return 0L;
        long ms = 0;
        Matcher m = P.matcher(s);
        while (m.find()) {
            long v = Long.parseLong(m.group(1));
            switch (Character.toLowerCase(m.group(2).charAt(0))) {
                case 's' -> ms += v * 1000L;
                case 'm' -> ms += v * 60_000L;
                case 'h' -> ms += v * 3_600_000L;
                case 'd' -> ms += v * 86_400_000L;
                case 'w' -> ms += v * 604_800_000L;
            }
        }
        return ms;
    }
    public static String fmtRemaining(long ms){
        if (ms <= 0) return "0s";
        long s = ms/1000;
        long w = s/604800; s%=604800;
        long d = s/86400; s%=86400;
        long h = s/3600; s%=3600;
        long m = s/60; s%=60;
        StringBuilder b=new StringBuilder();
        if(w>0)b.append(w).append("w ");
        if(d>0)b.append(d).append("d ");
        if(h>0)b.append(h).append("h ");
        if(m>0)b.append(m).append("m ");
        if(s>0)b.append(s).append("s");
        return b.toString().trim();
    }
}
