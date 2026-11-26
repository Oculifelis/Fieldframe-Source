package dev.fieldframe.core.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A parsed Controller Plate program. Grammar (one rule per line, case-insensitive keywords, {@code #} comments):
 * <pre>
 * program  := line*
 * line     := 'states' NAME (',' NAME)*        -- optional; first state is initial (default state: run)
 *           | 'reg' NAME (',' NAME)*           -- declare registers (max 8)
 *           | [ 'in' NAME ':' ] 'when' cond 'then' action (',' action)*
 * cond     := term ('or' term)*                -- 'and' binds tighter than 'or'; no parentheses
 * term     := atom ('and' atom)*
 * atom     := 'always' | 'not' atom
 *           | 'rise' 'ch' N | 'fall' 'ch' N
 *           | value CMP NUMBER ['%']
 *           | 'state' '==' NAME
 * value    := 'item' L | 'fluid' L | 'power' | 'ch' N | 'reg' NAME | NAME(declared register)
 * CMP      := '<' | '<=' | '>' | '>=' | '==' | '!='
 * action   := 'set' 'ch' N '=' NUMBER | 'pulse' 'ch' N
 *           | 'inc' NAME | 'dec' NAME | 'reset' NAME | NAME '=' NUMBER
 *           | 'goto' NAME
 * L        := 'a' | 'b' | 'c' ;  N := 1..8
 * </pre>
 * {@code power} compares RF, or percent of capacity with a trailing {@code %}. {@code item}/{@code fluid} compare item
 * count / mB in a lane, or percent of capacity with {@code %}.
 */
public final class ControllerProgram {
    public static final int MAX_RULES = 32;
    public static final int MAX_REGISTERS = 8;
    public static final int MAX_STATES = 16;
    public static final int MAX_LINE = 120;
    public static final int MAX_TEXT = 4096;
    public static final int MAX_ATOMS_PER_RULE = 8;
    public static final int MAX_ACTIONS_PER_RULE = 6;

    /** Parse failure with a 1-based line number and a human message. */
    public static final class ParseException extends Exception {
        public final int line;

        public ParseException(int line, String message) {
            super("line " + line + ": " + message);
            this.line = line;
        }
    }

    public enum Source { ITEM, FLUID, POWER, CHANNEL, REGISTER }

    public enum Cmp { LT, LE, GT, GE, EQ, NE }

    public sealed interface Cond permits Always, Not, Edge, Compare, InState, And, Or {
    }

    public record Always() implements Cond {
    }

    public record Not(Cond inner) implements Cond {
    }

    public record Edge(int channel, boolean rising) implements Cond {
    }

    public record Compare(Source source, int index, String register, Cmp cmp, long value, boolean percent) implements Cond {
    }

    public record InState(int state) implements Cond {
    }

    public record And(List<Cond> parts) implements Cond {
    }

    public record Or(List<Cond> parts) implements Cond {
    }

    public enum ActionKind { SET_CHANNEL, PULSE, INC, DEC, RESET, ASSIGN, GOTO }

    public record Action(ActionKind kind, int channel, int register, long value, int state) {
    }

    public record Rule(int line, int onlyInState, Cond cond, List<Action> actions) {
    }

    private final List<String> states;
    private final List<String> registers;
    private final List<Rule> rules;
    private final String source;

    private ControllerProgram(List<String> states, List<String> registers, List<Rule> rules, String source) {
        this.states = List.copyOf(states);
        this.registers = List.copyOf(registers);
        this.rules = List.copyOf(rules);
        this.source = source;
    }

    public List<String> states() {
        return states;
    }

    public List<String> registers() {
        return registers;
    }

    public List<Rule> rules() {
        return rules;
    }

    public String source() {
        return source;
    }

    public static ControllerProgram empty() {
        return new ControllerProgram(List.of("run"), List.of(), List.of(), "");
    }

    // ------------------------------------------------------------------ parser

    public static ControllerProgram parse(String text) throws ParseException {
        if (text == null) {
            text = "";
        }
        if (text.length() > MAX_TEXT) {
            throw new ParseException(1, "program longer than " + MAX_TEXT + " characters");
        }
        String[] lines = text.split("\n", -1);
        List<String> states = new ArrayList<>();
        Map<String, Integer> regs = new LinkedHashMap<>();
        // Pass 1: declarations, so rules may reference states/registers declared later.
        for (int i = 0; i < lines.length; i++) {
            String l = strip(lines[i]);
            if (l.isEmpty()) {
                continue;
            }
            if (l.length() > MAX_LINE) {
                throw new ParseException(i + 1, "line longer than " + MAX_LINE + " characters");
            }
            List<String> t = tokenize(l, i + 1);
            if (t.get(0).equals("states")) {
                for (String n : names(t, 1, i + 1)) {
                    if (states.contains(n)) {
                        throw new ParseException(i + 1, "state " + n + " declared twice");
                    }
                    states.add(n);
                }
            } else if (t.get(0).equals("reg")) {
                for (String n : names(t, 1, i + 1)) {
                    if (regs.containsKey(n)) {
                        throw new ParseException(i + 1, "register " + n + " declared twice");
                    }
                    regs.put(n, regs.size());
                }
            }
        }
        if (states.isEmpty()) {
            states.add("run");
        }
        if (states.size() > MAX_STATES) {
            throw new ParseException(1, "more than " + MAX_STATES + " states");
        }
        if (regs.size() > MAX_REGISTERS) {
            throw new ParseException(1, "more than " + MAX_REGISTERS + " registers");
        }
        List<Rule> rules = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String l = strip(lines[i]);
            if (l.isEmpty()) {
                continue;
            }
            List<String> t = tokenize(l, i + 1);
            if (t.get(0).equals("states") || t.get(0).equals("reg")) {
                continue;
            }
            Parser p = new Parser(t, i + 1, states, regs);
            rules.add(p.rule());
            if (rules.size() > MAX_RULES) {
                throw new ParseException(i + 1, "more than " + MAX_RULES + " rules");
            }
        }
        return new ControllerProgram(states, new ArrayList<>(regs.keySet()), rules, text);
    }

    private static String strip(String line) {
        int hash = line.indexOf('#');
        return (hash >= 0 ? line.substring(0, hash) : line).trim();
    }

    private static List<String> names(List<String> t, int from, int line) throws ParseException {
        List<String> out = new ArrayList<>();
        for (int i = from; i < t.size(); i++) {
            String s = t.get(i);
            if (s.equals(",")) {
                continue;
            }
            if (!s.matches("[a-z_][a-z0-9_]{0,15}")) {
                throw new ParseException(line, "invalid name '" + s + "'");
            }
            if (KEYWORDS.contains(s)) {
                throw new ParseException(line, "'" + s + "' is a keyword");
            }
            out.add(s.toUpperCase(Locale.ROOT).equals(s) ? s : s);
        }
        if (out.isEmpty()) {
            throw new ParseException(line, "expected at least one name");
        }
        return out;
    }

    private static final List<String> KEYWORDS = List.of("when", "then", "and", "or", "not", "always", "rise", "fall", "item", "fluid",
            "power", "ch", "reg", "state", "states", "set", "pulse", "inc", "dec", "reset", "goto", "in");

    static List<String> tokenize(String l, int line) throws ParseException {
        List<String> out = new ArrayList<>();
        String s = l.toLowerCase(Locale.ROOT);
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (Character.isLetter(c) || c == '_') {
                int j = i;
                while (j < s.length() && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_')) {
                    j++;
                }
                out.add(s.substring(i, j));
                i = j;
            } else if (Character.isDigit(c) || (c == '-' && i + 1 < s.length() && Character.isDigit(s.charAt(i + 1)))) {
                int j = i + 1;
                while (j < s.length() && Character.isDigit(s.charAt(j))) {
                    j++;
                }
                out.add(s.substring(i, j));
                i = j;
            } else if ("<>=!".indexOf(c) >= 0) {
                if (i + 1 < s.length() && s.charAt(i + 1) == '=') {
                    out.add(s.substring(i, i + 2));
                    i += 2;
                } else {
                    out.add(String.valueOf(c));
                    i++;
                }
            } else if (c == ',' || c == ':' || c == '%') {
                out.add(String.valueOf(c));
                i++;
            } else {
                throw new ParseException(line, "unexpected character '" + c + "'");
            }
        }
        if (out.isEmpty()) {
            throw new ParseException(line, "empty line");
        }
        return out;
    }

    private static final class Parser {
        private final List<String> t;
        private final int line;
        private final List<String> states;
        private final Map<String, Integer> regs;
        private int pos;
        private int atoms;

        Parser(List<String> t, int line, List<String> states, Map<String, Integer> regs) {
            this.t = t;
            this.line = line;
            this.states = states;
            this.regs = regs;
        }

        private ParseException err(String m) {
            return new ParseException(line, m);
        }

        private String peek() {
            return pos < t.size() ? t.get(pos) : "";
        }

        private String next() throws ParseException {
            if (pos >= t.size()) {
                throw err("unexpected end of line");
            }
            return t.get(pos++);
        }

        private void expect(String s) throws ParseException {
            String n = next();
            if (!n.equals(s)) {
                throw err("expected '" + s + "' but found '" + n + "'");
            }
        }

        private long number() throws ParseException {
            String n = next();
            try {
                long v = Long.parseLong(n);
                if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
                    throw err("number out of range: " + n);
                }
                return v;
            } catch (NumberFormatException e) {
                throw err("expected a number but found '" + n + "'");
            }
        }

        private int channel() throws ParseException {
            long n = number();
            if (n < 1 || n > 8) {
                throw err("channel must be 1..8");
            }
            return (int) n - 1;
        }

        private int lane() throws ParseException {
            String n = next();
            int i = "abc".indexOf(n);
            if (n.length() != 1 || i < 0) {
                throw err("lane must be A, B or C");
            }
            return i;
        }

        private int state(String n) throws ParseException {
            int i = states.indexOf(n);
            if (i < 0) {
                throw err("unknown state '" + n + "' (declare it with: states " + n + ")");
            }
            return i;
        }

        private int register(String n) throws ParseException {
            Integer i = regs.get(n);
            if (i == null) {
                throw err("unknown register '" + n + "' (declare it with: reg " + n + ")");
            }
            return i;
        }

        Rule rule() throws ParseException {
            int only = -1;
            if (peek().equals("in")) {
                next();
                only = state(next());
                expect(":");
            }
            expect("when");
            Cond c = or();
            expect("then");
            List<Action> actions = new ArrayList<>();
            actions.add(action());
            while (peek().equals(",")) {
                next();
                actions.add(action());
                if (actions.size() > MAX_ACTIONS_PER_RULE) {
                    throw err("more than " + MAX_ACTIONS_PER_RULE + " actions in one rule");
                }
            }
            if (pos != t.size()) {
                throw err("unexpected '" + peek() + "' after actions");
            }
            return new Rule(line, only, c, actions);
        }

        private Cond or() throws ParseException {
            List<Cond> parts = new ArrayList<>();
            parts.add(and());
            while (peek().equals("or")) {
                next();
                parts.add(and());
            }
            return parts.size() == 1 ? parts.get(0) : new Or(parts);
        }

        private Cond and() throws ParseException {
            List<Cond> parts = new ArrayList<>();
            parts.add(atom());
            while (peek().equals("and")) {
                next();
                parts.add(atom());
            }
            return parts.size() == 1 ? parts.get(0) : new And(parts);
        }

        private Cond atom() throws ParseException {
            if (++atoms > MAX_ATOMS_PER_RULE) {
                throw err("more than " + MAX_ATOMS_PER_RULE + " conditions in one rule");
            }
            String w = next();
            switch (w) {
                case "always":
                    return new Always();
                case "not":
                    return new Not(atom());
                case "rise":
                case "fall":
                    expect("ch");
                    return new Edge(channel(), w.equals("rise"));
                case "state": {
                    expect("==");
                    return new InState(state(next()));
                }
                default:
                    break;
            }
            Source src;
            int index = -1;
            String reg = null;
            switch (w) {
                case "item" -> {
                    src = Source.ITEM;
                    index = lane();
                }
                case "fluid" -> {
                    src = Source.FLUID;
                    index = lane();
                }
                case "power" -> src = Source.POWER;
                case "ch" -> {
                    src = Source.CHANNEL;
                    index = channel();
                }
                case "reg" -> {
                    src = Source.REGISTER;
                    reg = next();
                    index = register(reg);
                }
                default -> {
                    src = Source.REGISTER;
                    reg = w;
                    index = register(w);
                }
            }
            Cmp cmp = switch (next()) {
                case "<" -> Cmp.LT;
                case "<=" -> Cmp.LE;
                case ">" -> Cmp.GT;
                case ">=" -> Cmp.GE;
                case "==", "=" -> Cmp.EQ;
                case "!=" -> Cmp.NE;
                default -> throw err("expected a comparison (<, <=, >, >=, ==, !=)");
            };
            long value = number();
            boolean percent = false;
            if (peek().equals("%")) {
                next();
                percent = true;
                if (src == Source.CHANNEL || src == Source.REGISTER) {
                    throw err("'%' only applies to item, fluid and power");
                }
                if (value < 0 || value > 100) {
                    throw err("percent must be 0..100");
                }
            }
            return new Compare(src, index, reg, cmp, value, percent);
        }

        private Action action() throws ParseException {
            String w = next();
            switch (w) {
                case "set": {
                    expect("ch");
                    int ch = channel();
                    expect("=");
                    long v = number();
                    if (v < 0 || v > 15) {
                        throw err("channel value must be 0..15");
                    }
                    return new Action(ActionKind.SET_CHANNEL, ch, -1, v, -1);
                }
                case "pulse": {
                    expect("ch");
                    return new Action(ActionKind.PULSE, channel(), -1, 15, -1);
                }
                case "inc":
                    return new Action(ActionKind.INC, -1, register(next()), 1, -1);
                case "dec":
                    return new Action(ActionKind.DEC, -1, register(next()), 1, -1);
                case "reset":
                    return new Action(ActionKind.RESET, -1, register(next()), 0, -1);
                case "goto":
                    return new Action(ActionKind.GOTO, -1, -1, 0, state(next()));
                default: {
                    int r = register(w);
                    expect("=");
                    return new Action(ActionKind.ASSIGN, -1, r, number(), -1);
                }
            }
        }
    }
}
