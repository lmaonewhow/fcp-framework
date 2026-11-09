package dev.fcp.generator;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Stabilizes only recognized volatile fields of the locked RuoYi generator templates.
 * Baseline bytes, never a user's current working file, supply the stable values.
 * Unknown encodings, ambiguous dates, or changed SQL template shapes are left intact.
 */
public final class RuoYiGeneratedMetadata {
    private static final Pattern DATE = Pattern.compile("(?m)^ \\* @date ([0-9]{4}-[0-9]{2}-[0-9]{2})(?=\\r?$)");
    private static final String INSERT = "insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)";
    private static final Pattern PREFIX = Pattern.compile("^values\\(([0-9]{1,19}), '([^'\\\\\\r\\n]*)', ('[0-9]{1,19}'|[0-9]{1,19}),");
    private static final Pattern TOKEN = Pattern.compile("'(?:[^'\\\\\\r\\n])*'|[0-9]+|sysdate\\(\\)|null");
    private record Row(String id, int idStart, int idEnd, int parentStart, int parentEnd) {}
    private record Edit(int start, int end, String value) {}
    private RuoYiGeneratedMetadata() {}

    public static byte[] stabilize(String path, byte[] candidate, byte[] baseline) {
        if (baseline == null) return candidate;
        try {
            String next = decode(candidate), prior = decode(baseline), stable = next;
            if (path.startsWith("integration/ruoyi/src/main/java/") && path.endsWith(".java")) stable = stabilizeDate(next, prior);
            else if (path.startsWith("generated/sql/") && path.endsWith("Menu.sql")) stable = stabilizeMenu(next, prior);
            return stable.equals(next) ? candidate : stable.getBytes(StandardCharsets.UTF_8);
        } catch (CharacterCodingException ignored) { return candidate; }
    }
    private static String decode(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
    private static String stabilizeDate(String next, String prior) {
        Matcher candidate = DATE.matcher(next), baseline = DATE.matcher(prior);
        if (!candidate.find() || !baseline.find()) return next;
        int start = candidate.start(1), end = candidate.end(1);
        String date = candidate.group(1), oldDate = baseline.group(1);
        if (candidate.find() || baseline.find()) return next;
        try { LocalDate.parse(date); LocalDate.parse(oldDate); }
        catch (DateTimeParseException ignored) { return next; }
        return next.substring(0,start) + oldDate + next.substring(end);
    }
    private static String stabilizeMenu(String next, String prior) {
        List<Row> candidate = rows(next), baseline = rows(prior);
        if (candidate == null || baseline == null) return next;
        List<Edit> edits = new ArrayList<>();
        for (int i=0;i<6;i++) {
            Row row=candidate.get(i);
            edits.add(new Edit(row.idStart,row.idEnd,baseline.get(i).id));
            if(i>0) edits.add(new Edit(row.parentStart,row.parentEnd,baseline.get(0).id));
        }
        edits.sort(Comparator.comparingInt(Edit::start).reversed());
        StringBuilder result = new StringBuilder(next);
        for (Edit edit : edits) result.replace(edit.start,edit.end,edit.value);
        return result.toString();
    }
    private static List<Row> rows(String sql) {
        List<Row> rows = new ArrayList<>(); boolean needsValues=false; int offset=0;
        for (String raw : sql.split("\n",-1)) {
            String line=raw.endsWith("\r")?raw.substring(0,raw.length()-1):raw;
            if (line.isBlank() || line.equals("-- 菜单 SQL") || line.equals("-- 按钮 SQL")) {
                if(needsValues)return null;
            } else if (line.equals(INSERT)) {
                if(needsValues || rows.size()>=6)return null;
                needsValues=true;
            } else {
                if(!needsValues || !line.endsWith(");"))return null;
                Matcher prefix=PREFIX.matcher(line);
                if(!prefix.find())return null;
                List<String> tokens=tokens(line.substring(7,line.length()-2));
                int index=rows.size();
                if(tokens==null || tokens.size()!=19 || !templateTokens(tokens,index))return null;
                if(index>0 && !tokens.get(2).equals(rows.get(0).id))return null;
                if(index==0 && !tokens.get(2).matches("'[0-9]{1,19}'"))return null;
                rows.add(new Row(prefix.group(1),offset+prefix.start(1),offset+prefix.end(1),offset+prefix.start(3),offset+prefix.end(3)));
                needsValues=false;
            }
            offset+=raw.length()+1;
        }
        if(needsValues || rows.size()!=6 || rows.stream().map(Row::id).distinct().count()!=6)return null;
        return rows;
    }
    private static List<String> tokens(String body) {
        List<String> out=new ArrayList<>(); int position=0;
        while(position<body.length()) {
            while(position<body.length() && body.charAt(position)==' ')position++;
            Matcher token=TOKEN.matcher(body);token.region(position,body.length());
            if(!token.lookingAt())return null;
            out.add(token.group());position=token.end();
            while(position<body.length() && body.charAt(position)==' ')position++;
            if(position==body.length())return out;
            if(body.charAt(position++)!=',' || position==body.length())return null;
        }
        return null;
    }
    private static boolean templateTokens(List<String> t,int index) {
        if(!t.get(0).matches("[0-9]{1,19}") || !quoted(t.get(1)) || !quoted(t.get(4)) || !quoted(t.get(5)) || !quoted(t.get(11)) || !quoted(t.get(18)))return false;
        if(!t.get(3).equals("'"+(index==0?1:index)+"'") || !t.get(6).equals("1") || !t.get(7).equals("0") || !t.get(8).equals(index==0?"'C'":"'F'"))return false;
        if(index>0 && (!t.get(4).equals("'#'") || !t.get(5).equals("''") || !t.get(18).equals("''")))return false;
        return t.get(9).equals("'0'") && t.get(10).equals("'0'") && t.get(12).equals("'#'") && t.get(13).equals("103") && t.get(14).equals("1") && t.get(15).equals("sysdate()") && t.get(16).equals("null") && t.get(17).equals("null");
    }
    private static boolean quoted(String token) { return token.startsWith("'") && token.endsWith("'"); }
}
