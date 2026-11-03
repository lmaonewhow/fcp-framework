package fcp;

import java.math.BigDecimal;
import java.util.*;

/** Bounded JSON codec. Decimal tokens are parsed exactly, never through binary double.
 * Financial amounts and integers beyond the JS safe range must remain typed strings.
 */
public final class Json {
    private static final int MAX_DEPTH = 32;
    private static final int MAX_TEXT = 16 * 1024 * 1024;
    private static final int MAX_NUMBER_TOKEN = 128;
    private static final int MAX_SCALE = 1000;
    private static final BigDecimal MAX_NUMBER = new BigDecimal("9007199254740991");
    private final String text;
    private int at;
    private Json(String text) { this.text = text; }

    public static Object parse(String text) {
        if (text == null || text.length() > MAX_TEXT) throw new IllegalArgumentException("JSON text limit");
        Json parser = new Json(text);
        Object value = parser.read(0);
        parser.whitespace();
        if (parser.at != text.length()) throw new IllegalArgumentException("Trailing JSON content");
        return value;
    }

    private static void scalarString(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++i)))
                    throw new IllegalArgumentException("Unpaired Unicode surrogate");
            } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("Unpaired Unicode surrogate");
        }
    }
    private static BigDecimal boundedNumber(BigDecimal value) {
        if (value.precision() > MAX_NUMBER_TOKEN || Math.abs((long)value.scale()) > MAX_SCALE)
            throw new IllegalArgumentException("Numeric precision/exponent limit");
        if (value.abs().compareTo(MAX_NUMBER) > 0) throw new IllegalArgumentException("Large numbers must be strings");
        return value;
    }
    private void whitespace() { while (at < text.length() && " \t\r\n".indexOf(text.charAt(at)) >= 0) at++; }
    private boolean take(char c) { if (at < text.length() && text.charAt(at) == c) {at++;return true;} return false; }
    private void need(char c) { if (!take(c)) throw new IllegalArgumentException("Expected " + c); }

    private Object read(int depth) {
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("JSON depth limit");
        whitespace();
        if (at >= text.length()) throw new IllegalArgumentException("Unexpected EOF");
        char c = text.charAt(at);
        if (c == '"') return string();
        if (c == '{') {
            at++;
            Map<String,Object> object = new LinkedHashMap<>();
            whitespace(); if (take('}')) return object;
            for (;;) {
                whitespace(); String key = string(); whitespace(); need(':'); Object value = read(depth + 1);
                if (object.containsKey(key)) throw new IllegalArgumentException("Duplicate JSON key");
                object.put(key,value); whitespace(); if (take('}')) return object; need(',');
            }
        }
        if (c == '[') {
            at++; List<Object> values = new ArrayList<>(); whitespace(); if(take(']')) return values;
            for (;;) {values.add(read(depth + 1));whitespace();if(take(']'))return values;need(',');}
        }
        for (String literal : new String[]{"true","false","null"}) {
            if (text.startsWith(literal,at)) {at+=literal.length();return literal.equals("null")?null:Boolean.valueOf(literal);}
        }
        int start=at;
        while(at<text.length() && "0123456789+-.eE".indexOf(text.charAt(at))>=0) {
            at++; if(at-start>MAX_NUMBER_TOKEN)throw new IllegalArgumentException("Number token limit");
        }
        String token=text.substring(start,at);
        if(!token.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))throw new IllegalArgumentException("Invalid JSON number");
        return boundedNumber(new BigDecimal(token));
    }
    private String string() {
        need('"'); StringBuilder output=new StringBuilder();
        while(at<text.length()) {
            char c=text.charAt(at++);
            if(c=='"'){String value=output.toString();scalarString(value);return value;}
            if(c<32)throw new IllegalArgumentException("Control character in JSON string");
            if(c!='\\'){output.append(c);continue;}
            if(at==text.length())throw new IllegalArgumentException("Incomplete escape");
            switch(text.charAt(at++)) {
                case '"' -> output.append('"'); case '\\' -> output.append('\\'); case '/' -> output.append('/');
                case 'b' -> output.append('\b'); case 'f' -> output.append('\f'); case 'n' -> output.append('\n');
                case 'r' -> output.append('\r'); case 't' -> output.append('\t');
                case 'u' -> {
                    if(at+4>text.length())throw new IllegalArgumentException("Incomplete Unicode escape");
                    int code=0;
                    for(int i=0;i<4;i++) {
                        char h=text.charAt(at++);
                        int digit=h>='0'&&h<='9'?h-'0':h>='a'&&h<='f'?h-'a'+10:h>='A'&&h<='F'?h-'A'+10:-1;
                        if(digit<0)throw new IllegalArgumentException("Non-hex Unicode escape");
                        code=code*16+digit;
                    }
                    output.append((char)code);
                }
                default -> throw new IllegalArgumentException("Unknown JSON escape");
            }
        }
        throw new IllegalArgumentException("Unterminated JSON string");
    }
    public static Map<String,Object> obj(Object... pairs) {
        if(pairs.length%2!=0)throw new IllegalArgumentException("Expected key/value pairs");
        Map<String,Object> result=new LinkedHashMap<>();
        for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;
    }

    /** Validating structural copy with exactly the same scalar normalization as write/parse.
     * Avoids allocating an intermediate whole-document JSON string at in-process boundaries.
     * Mutable maps/lists are never shared across the provider boundary.
     */
    public static Object copy(Object value) {
        return copy(value, 0, Collections.newSetFromMap(new IdentityHashMap<>()), new CopyBudget());
    }
    private static final class CopyBudget {
        long characters;
        void add(long count) {
            characters += count;
            if (characters > MAX_TEXT) throw new IllegalArgumentException("JSON text limit");
        }
        void string(String text) {
            scalarString(text);
            add(2);
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                add(c == '"' || c == '\\' || c == '\n' || c == '\r' || c == '\t' ? 2 : c < 32 ? 6 : 1);
            }
        }
    }
    private static Object copy(Object value, int depth, Set<Object> ancestors, CopyBudget budget) {
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("JSON depth limit");
        if (value == null) { budget.add(4); return null; }
        if (value instanceof String text) { budget.string(text); return text; }
        if (value instanceof Boolean flag) { budget.add(flag ? 4 : 5); return flag; }
        if (value instanceof Number number) {
            BigDecimal decimal = boundedNumber(number instanceof BigDecimal d ? d : new BigDecimal(number.toString()));
            String canonical = decimal.stripTrailingZeros().toPlainString();
            // The old write/parse boundary rejected plain number tokens over 128 characters.
            if (canonical.length() > MAX_NUMBER_TOKEN) throw new IllegalArgumentException("Number token limit");
            budget.add(canonical.length());
            return new BigDecimal(canonical);
        }
        if (!ancestors.add(value)) throw new IllegalArgumentException("Cyclic JSON value");
        try {
            if (value instanceof Map<?, ?> object) {
                budget.add(2);
                Map<String, Object> result = new LinkedHashMap<>();
                boolean first = true;
                for (var entry : object.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException("JSON key must be a string");
                    if (!first) budget.add(1);
                    first = false;
                    budget.string(key); budget.add(1);
                    result.put(key, copy(entry.getValue(), depth + 1, ancestors, budget));
                }
                return result;
            }
            if (value instanceof Iterable<?> array) {
                budget.add(2);
                List<Object> result = new ArrayList<>();
                boolean first = true;
                for (Object item : array) {
                    if (!first) budget.add(1);
                    first = false;
                    result.add(copy(item, depth + 1, ancestors, budget));
                }
                return result;
            }
            throw new IllegalArgumentException("Unsupported JSON value type");
        } finally { ancestors.remove(value); }
    }

    public static String write(Object value) {
        StringBuilder out=new StringBuilder();
        encode(value,out,0,Collections.newSetFromMap(new IdentityHashMap<>()));
        return out.toString();
    }
    private static void encode(Object value,StringBuilder out,int depth,Set<Object> ancestors) {
        if(depth>MAX_DEPTH)throw new IllegalArgumentException("JSON depth limit");
        if(value==null)out.append("null");
        else if(value instanceof String text){scalarString(text);quote(text,out);}
        else if(value instanceof Boolean)out.append(value);
        else if(value instanceof Number n)out.append(boundedNumber(n instanceof BigDecimal d?d:new BigDecimal(n.toString())).stripTrailingZeros().toPlainString());
        else if(value instanceof Map<?,?> object) {
            if(!ancestors.add(value))throw new IllegalArgumentException("Cyclic JSON object");
            out.append('{');boolean first=true;
            for(var e:object.entrySet()) {
                if(!(e.getKey() instanceof String key))throw new IllegalArgumentException("JSON key must be a string");
                if(!first)out.append(',');first=false;scalarString(key);quote(key,out);out.append(':');encode(e.getValue(),out,depth+1,ancestors);
            }
            out.append('}');ancestors.remove(value);
        } else if(value instanceof Iterable<?> array) {
            if(!ancestors.add(value))throw new IllegalArgumentException("Cyclic JSON array");
            out.append('[');boolean first=true;
            for(Object item:array){if(!first)out.append(',');first=false;encode(item,out,depth+1,ancestors);}
            out.append(']');ancestors.remove(value);
        } else throw new IllegalArgumentException("Unsupported JSON value type");
        if(out.length()>MAX_TEXT)throw new IllegalArgumentException("JSON text limit");
    }
    private static void quote(String text,StringBuilder out) {
        out.append('"');
        for(char c:text.toCharArray())switch(c) {
            case '"' -> out.append("\\\"");case '\\' -> out.append("\\\\");case '\n' -> out.append("\\n");
            case '\r' -> out.append("\\r");case '\t' -> out.append("\\t");
            default -> {if(c<32)out.append(String.format("\\u%04x",(int)c));else out.append(c);}
        }
        out.append('"');
    }
}
