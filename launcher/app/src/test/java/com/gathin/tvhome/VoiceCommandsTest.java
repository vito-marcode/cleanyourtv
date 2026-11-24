package com.gathin.tvhome;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class VoiceCommandsTest {
    private static String parse(String phrase) {
        return VoiceCommands.parse(phrase).toString();
    }

    @Test public void actions() {
        assertEquals("ACTION:home", parse("Vai alla home"));
        assertEquals("ACTION:home", parse("torna a casa per favore"));
        assertEquals("ACTION:back", parse("Indietro"));
        assertEquals("ACTION:mute", parse("togli l'audio"));
        assertEquals("ACTION:mute", parse("Muto!"));
        assertEquals("ACTION:volume_up", parse("alza il volume"));
        assertEquals("ACTION:volume_up", parse("alza un po' il volume"));
        assertEquals("ACTION:volume_down", parse("abbassa il volume"));
        assertEquals("ACTION:volume_down", parse("più piano"));
        assertEquals("ACTION:settings", parse("apri le impostazioni"));
        assertEquals("ACTION:wifi", parse("impostazioni wi-fi"));
        assertEquals("ACTION:screensaver", parse("salvaschermo"));
    }

    @Test public void video_search_goes_to_youtube() {
        assertEquals("VIDEO_SEARCH:gatti", parse("cerca gatti su YouTube"));
        assertEquals("VIDEO_SEARCH:gatti", parse("Cerca su YouTube gatti"));
        assertEquals("VIDEO_SEARCH:gatti divertenti", parse("youtube gatti divertenti"));
        assertEquals("VIDEO_SEARCH:la ricetta della carbonara", parse("metti la ricetta della carbonara su youtube"));
        assertEquals("VIDEO_SEARCH:ricette di pasta", parse("cerca ricette di pasta"));
        assertEquals("VIDEO_SEARCH:vasco rossi", parse("cerca un video di Vasco Rossi"));
        assertEquals("VIDEO_SEARCH:gatti", parse("guarda gatti su smarttube"));
    }

    @Test public void open_and_watch() {
        assertEquals("OPEN:netflix", parse("Apri Netflix"));
        assertEquals("OPEN:spotify", parse("avvia l'app Spotify"));
        assertEquals("OPEN:youtube", parse("apri YouTube"));
        assertEquals("WATCH:metti rai 2", parse("Metti Rai 2"));
        assertEquals("WATCH:metti rai due", parse("metti rai due per favore"));
        assertEquals("WATCH:il canale 5", parse("il canale 5"));
        assertEquals("WATCH:netflix", parse("Netflix"));
        assertEquals("NONE:", parse("grazie"));
    }

    private static final List<VoiceCommands.App> APPS = Arrays.asList(
            new VoiceCommands.App("org.smarttube.stable", "SmartTube"),
            new VoiceCommands.App("com.netflix.ninja", "Netflix"),
            new VoiceCommands.App("com.amazon.amazonvideo.livingroom", "Prime Video"),
            new VoiceCommands.App("it.mdv.dttchannels", "Canali TV"),
            new VoiceCommands.App("com.spotify.tv.android", "Spotify"),
            new VoiceCommands.App("hls.player", "HLS Player"),
            new VoiceCommands.App("com.android.tv.settings", "Impostazioni"));

    private static String app(String spoken) {
        VoiceCommands.App a = VoiceCommands.findApp(spoken, APPS);
        return a == null ? null : a.pkg;
    }

    @Test public void apps_by_spoken_name() {
        assertEquals("com.netflix.ninja", app("netflix"));
        assertEquals("org.smarttube.stable", app("youtube"));
        assertEquals("org.smarttube.stable", app("smart tube"));
        assertEquals("com.amazon.amazonvideo.livingroom", app("prime"));
        assertEquals("com.amazon.amazonvideo.livingroom", app("amazon prime video"));
        assertEquals("it.mdv.dttchannels", app("la tv"));
        assertEquals("com.spotify.tv.android", app("spotifai"));
        assertEquals("hls.player", app("hls player"));
        assertNull(app("rai 2"));
        assertNull(app("che tempo fa"));
    }

    @Test public void without_verb_and_url() {
        assertEquals("netflix", VoiceCommands.withoutVerb("Metti Netflix"));
        assertEquals("spotify", VoiceCommands.withoutVerb("passa a spotify"));
        assertEquals("https://www.youtube.com/results?search_query=gatti+divertenti",
                VoiceCommands.youtubeSearchUrl("gatti divertenti"));
    }
}
