package com.gathin.tvhome;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Italian voice commands for the mic key (app_keys target "voice", VoiceSession). Pure Java, so the JVM tests
 * cover it. A phrase becomes one command:
 * <ul>
 *   <li>{@link Kind#ACTION}: "home", "indietro", "muto", "alza il volume", "impostazioni"... (arg: the action word,
 *       one of RemoteKeys' or "volume_up" / "volume_down");</li>
 *   <li>{@link Kind#VIDEO_SEARCH}: "cerca gatti su youtube", "youtube gatti", "cerca ricette di pasta"
 *       (a bare "cerca" searches videos; arg: the search text);</li>
 *   <li>{@link Kind#OPEN}: "apri netflix", "avvia spotify" (arg: the app's spoken name; a channel if no app);</li>
 *   <li>{@link Kind#WATCH}: a channel verb, "metti rai 2", "il canale 5", "guarda la7" (arg: the whole phrase; the
 *       session asks Canali TV whether it names a channel, then tries it as an app name);</li>
 *   <li>{@link Kind#NAME}: no verb, "sky", "netflix", "rai 2" (arg: the phrase; an app of that name first, then a
 *       channel: "sky" opens the Sky app, "metti sky" tunes Sky TG24).</li>
 * </ul>
 */
final class VoiceCommands {

    enum Kind { ACTION, VIDEO_SEARCH, OPEN, WATCH, NAME, NONE }

    /** A leading channel verb: the phrase asks for a channel first (WATCH), not an app (NAME). */
    private static final Pattern CHANNEL_VERB = Pattern.compile(
            "^(?:metti(?:mi)?|guarda|voglio vedere|fammi vedere|sintonizza|passa|cambia|vai su|vai sul|il canale|canale)\\b.*");

    static final class Command {
        final Kind kind;
        final String arg;

        Command(Kind kind, String arg) {
            this.kind = kind;
            this.arg = arg;
        }

        @Override public String toString() {
            return kind + ":" + arg;
        }
    }

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_WORD = Pattern.compile("[^a-z0-9]+");

    /** Politeness and fillers dropped at either end: "per favore metti rai 2 grazie". */
    private static final List<String> FILLERS = Arrays.asList(
            "per favore", "per piacere", "grazie", "ok google", "ehi google", "dai", "allora", "ora", "adesso");

    /** "youtube", as the recogniser writes it, and SmartTube by name. */
    private static final String YT = "(?:youtube|you tube|smarttube|smart tube)";
    private static final Pattern VIDEO = Pattern.compile(
            "^(?:(?:cerca(?:mi)?|trova(?:mi)?|metti|guarda|riproduci|fammi vedere|voglio vedere)\\s+)?"
            + "(?:(?:su|in|con)\\s+" + YT + "\\s+|" + YT + "\\s+)(.+)$");
    private static final Pattern VIDEO_AFTER = Pattern.compile(
            "^(?:cerca(?:mi)?|trova(?:mi)?|metti|guarda|riproduci|fammi vedere|voglio vedere)\\s+(.+?)\\s+(?:su|in|con)\\s+" + YT + "$");
    private static final Pattern SEARCH = Pattern.compile("^(?:cerca(?:mi)?|trova(?:mi)?|ricerca)\\s+(?:(?:un |il |i )?video\\s+(?:di |su )?)?(.+)$");
    private static final Pattern OPEN = Pattern.compile(
            "^(?:apri(?:mi)?|avvia|lancia|fai partire|vai su|vai in|vai a|entra in|accendi)\\s+(?:l |la |il |lo |le |gli |i )?(?:app |applicazione )?(.+)$");

    private VoiceCommands() { }

    /** Lowercase, no accents, words split on anything that isn't a letter or digit: "Rai2, per favore!" -> "rai2 per favore". */
    static String normalize(String text) {
        if (text == null) return "";
        String low = text.toLowerCase(Locale.ITALIAN);
        String plain = Normalizer.normalize(low, Normalizer.Form.NFD);
        plain = MARKS.matcher(plain).replaceAll("");
        return NON_WORD.matcher(plain).replaceAll(" ").trim();
    }

    private static String trimFillers(String s) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String f : FILLERS) {
                if (s.equals(f)) return "";
                if (s.startsWith(f + " ")) { s = s.substring(f.length() + 1); changed = true; }
                if (s.endsWith(" " + f)) { s = s.substring(0, s.length() - f.length() - 1); changed = true; }
            }
        }
        return s.trim();
    }

    static Command parse(String phrase) {
        String s = trimFillers(normalize(phrase));
        if (s.isEmpty()) return new Command(Kind.NONE, "");

        String action = action(s);
        if (action == null) action = action(bareAction(s));   // "metti la home", "metti muto", "vai alla home"
        if (action != null) return new Command(Kind.ACTION, action);

        Matcher m = VIDEO_AFTER.matcher(s);
        if (m.matches()) return new Command(Kind.VIDEO_SEARCH, searchText(m.group(1)));
        m = VIDEO.matcher(s);
        if (m.matches()) return new Command(Kind.VIDEO_SEARCH, searchText(m.group(1)));

        m = OPEN.matcher(s);
        if (m.matches()) {
            String what = m.group(1);
            String inner = action(what);                      // "apri le impostazioni"
            if (inner != null) return new Command(Kind.ACTION, inner);
            return new Command(Kind.OPEN, what);
        }
        m = SEARCH.matcher(s);
        if (m.matches()) return new Command(Kind.VIDEO_SEARCH, searchText(m.group(1)));
        return new Command(CHANNEL_VERB.matcher(s).matches() ? Kind.WATCH : Kind.NAME, s);
    }

    /** The phrase without a leading verb and article: "metti la home" -> "home", so it is never taken for a channel. */
    static String bareAction(String s) {
        return withoutVerb(s).replaceFirst("^(?:vai |torna )?(?:alla |al |a |la |il |lo |l )", "");
    }

    /** A first hypothesis that is only a verb (and its preposition or article): the phrase was cut. */
    private static final Pattern CUT = Pattern.compile(
            "^(?:apri(?:mi)?|avvia|lancia|metti(?:mi)?|guarda|cerca(?:mi)?|trova|vai|passa|sintonizza)(?: (?:su|sul|a|al|alla|il|la|lo|l))?$");

    /**
     * The phrase to act on: the recogniser's first hypothesis, unless it is only a verb and a longer one starts with it. A push-to-talk
     * release can cut the phrase, and the recogniser then ranks the cut "Apri" above "Apri Sky".
     */
    static String bestPhrase(java.util.List<String> heard) {
        String first = heard.get(0);
        String norm = normalize(first);
        if (!CUT.matcher(norm).matches()) return first;     // a complete phrase: an extension is noise ("… maggio")
        String best = first;
        for (String h : heard) {
            String n = normalize(h);
            if (n.length() > normalize(best).length() && n.startsWith(norm + " ")) best = h;
        }
        return best;
    }

    /** The search text without a dangling preposition: the recogniser sometimes drops the final "YouTube"
     *  ("cerca recensione galaxy fold 8 su"). */
    static String searchText(String q) {
        return q.replaceFirst("(?:\\s+(?:su|sul|in|con|di|da)(?:\\s+you)?)+$", "").trim();
    }

    /** The action word a whole phrase asks for, or null. */
    static String action(String s) {
        switch (s) {
            case "home": case "casa": case "menu": case "menu principale": case "schermata principale":
            case "vai alla home": case "torna alla home": case "torna a casa": case "vai a casa": case "torna al menu":
            case "pagina iniziale": case "schermata home":
                return "home";
            case "indietro": case "torna indietro": case "vai indietro": case "esci":
                return "back";
            case "muto": case "silenzio": case "togli l audio": case "togli audio": case "disattiva l audio":
            case "metti muto": case "togli il muto": case "riattiva l audio": case "audio":
                return "mute";
            case "impostazioni": case "settings": case "opzioni":
                return "settings";
            case "wifi": case "wi fi": case "impostazioni wifi": case "impostazioni wi fi": case "rete":
                return "wifi";
            case "salvaschermo": case "screensaver": case "screen saver":
                return "screensaver";
            case "app recenti": case "recenti":
                return "recents";
            default:
                break;
        }
        // a level: "volume al massimo", "metti il volume a 8", "volume a meta" -> "volume_set:<0-100 %>"
        java.util.regex.Matcher lvl = Pattern.compile(
                "^(?:(?:metti|imposta|porta|regola)\\s+)?(?:il\\s+)?volume\\s+(?:al|a|alla|all)\\s+(.+)$").matcher(s);
        if (lvl.matches()) {
            String v = lvl.group(1).trim();
            switch (v) {
                case "massimo": case "max": case "tutto": return "volume_set:100";
                case "minimo": case "min": return "volume_set:7";
                case "meta": case "mezzo": return "volume_set:50";
                default:
                    if (v.matches("[0-9]{1,3}")) {
                        int n = Integer.parseInt(v);       // up to 15: the box's own steps; above: percent
                        return "volume_set:" + Math.min(100, n <= 15 ? Math.round(n * 100f / 15) : n);
                    }
            }
        }
        if (s.matches("(?:alza|aumenta)(?: (?:il|un po(?: il)?))? volume(?: .*)?|volume (?:su|piu alto|piu forte)|piu forte|alza"))
            return "volume_up";
        if (s.matches("(?:abbassa|diminuisci)(?: (?:il|un po(?: il)?))? volume(?: .*)?|volume (?:giu|piu basso)|piu piano|abbassa"))
            return "volume_down";
        return null;
    }

    // ---------------------------------------------------------------- apps by spoken name

    /** An installed app the launcher can open: its package and the label shown under its tile. */
    static final class App {
        final String pkg;
        final String label;

        App(String pkg, String label) {
            this.pkg = pkg;
            this.label = label;
        }
    }

    /** Spoken names that are not the label: "youtube" opens SmartTube, "prime" Prime Video, "tv" Canali TV... */
    private static final String[][] ALIASES = {
            {"youtube", "smarttube"}, {"you tube", "smarttube"}, {"prime", "prime video"}, {"amazon", "prime video"},
            {"amazon prime", "prime video"}, {"tv", "canali tv"}, {"televisione", "canali tv"}, {"la tv", "canali tv"},
            {"digitale terrestre", "canali tv"}, {"i canali", "canali tv"}, {"canali", "canali tv"},
            {"rai play", "raiplay"}, {"disney", "disney+"}, {"disney plus", "disney+"},
    };

    /**
     * The app a spoken name means: an alias, then the label equal, starting with, or containing the name (or the name
     * containing the label), then one or two letters off. Null when nothing is close.
     */
    static App findApp(String spoken, List<App> apps) {
        String name = normalize(spoken);
        if (name.isEmpty() || apps.isEmpty()) return null;
        for (String[] alias : ALIASES) {
            if (name.equals(alias[0])) {
                App a = findApp(alias[1], apps, false);
                if (a != null) return a;
            }
        }
        return findApp(name, apps, true);
    }

    private static App findApp(String name, List<App> apps, boolean fuzzy) {
        String compact = name.replace(" ", "");
        List<String[]> labels = new ArrayList<>();
        for (App a : apps) labels.add(new String[]{normalize(a.label), normalize(a.label).replace(" ", "")});
        for (int tier = 0; tier < 3; tier++) {
            for (int i = 0; i < apps.size(); i++) {
                String label = labels.get(i)[0], flat = labels.get(i)[1];
                if (flat.isEmpty()) continue;
                boolean hit;
                switch (tier) {
                    case 0: hit = flat.equals(compact); break;
                    case 1: hit = flat.startsWith(compact) && compact.length() >= 3; break;
                    default: hit = (compact.length() >= 4 && flat.contains(compact))
                            || (flat.length() >= 4 && (" " + name + " ").contains(" " + label + " "));
                }
                if (hit) return apps.get(i);
            }
        }
        if (!fuzzy || compact.length() < 4) return null;
        App best = null;
        int bestD = Integer.MAX_VALUE;
        for (int i = 0; i < apps.size(); i++) {
            String flat = labels.get(i)[1];
            int d = distance(compact, flat);
            if (d <= (compact.length() <= 6 ? 1 : 2) && d < bestD) { best = apps.get(i); bestD = d; }
        }
        return best;
    }

    static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int[] cur = new int[b.length() + 1];
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                cur[j] = Math.min(Math.min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            }
            prev = cur;
        }
        return prev[b.length()];
    }

    /** What WATCH tries as an app name: the phrase without its verb ("metti netflix" -> "netflix"). */
    static String withoutVerb(String phrase) {
        String s = normalize(phrase);
        return s.replaceFirst("^(?:metti(?:mi)?|guarda|voglio vedere|fammi vedere|vai su|passa(?: a| su)?|cambia su|sintonizza)\\s+(?:su )?", "");
    }

    /** The YouTube search URL SmartTube (and YouTube) open as a results page. */
    static String youtubeSearchUrl(String query) {
        try {
            return "https://www.youtube.com/results?search_query=" + java.net.URLEncoder.encode(query, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return "https://www.youtube.com/results?search_query=" + query.replace(' ', '+');
        }
    }
}
