package cn.autoplay.replacement;

import org.json.JSONObject;

/**
 * 媒体/歌词状态（{@code CMD_DASHBOARD_DATA 42} 子类型 {@code 1}）。
 *
 * <p>与 {@link NavigationText} 一样是<b>增量包</b>：盒子每次只带发生变化的
 * 1~6 个字段。播放中每约 0.5 s 会来一条 {@code {"MediaSongPlayTime":210534}}，
 * 如果把它当全量快照处理，歌词会被立刻清空。因此这里做字段级合并。
 *
 * <p>唯一的例外是 {@code MediaLyrics}：实测盒子用空串表示「该句结束 / 清空歌词」，
 * 所以它<b>允许空串覆盖</b>旧值；其余字符串字段空串仍视为「未更新」。
 */
final class MediaText {
    private String song = "";
    private String artist = "";
    private String album = "";
    private String lyrics = "";
    private String app = "";
    private int duration = -1;
    private int playTime = -1;
    private int playStatus = -1;

    void clear() {
        song = artist = album = lyrics = app = "";
        duration = playTime = playStatus = -1;
    }

    void update(JSONObject data) {
        if (data == null) return;

        song = string(data, "MediaSongName", song);
        artist = string(data, "MediaArtistName", artist);
        album = string(data, "MediaAlbumName", album);
        app = string(data, "MediaAPPName", app);
        // 歌词允许空串覆盖：盒子用 "" 表示这一句结束
        if (data.has("MediaLyrics")) {
            Object value = data.opt("MediaLyrics");
            if (value instanceof String) lyrics = ((String) value).trim();
        }
        duration = number(data, "MediaSongDuration", duration);
        playTime = number(data, "MediaSongPlayTime", playTime);
        playStatus = number(data, "MediaPlayStatus", playStatus);
    }

    /** 是否有可显示的内容。 */
    boolean hasContent() {
        return !lyrics.isEmpty() || !song.isEmpty();
    }

    /**
     * 歌词区单行文本。
     *
     * <p>有歌词就显示当前歌词（盒子逐句下发，一次只有一行）；
     * 没有歌词时退回「歌名 · 歌手」，保证副屏不至于空白。
     */
    String lyricLine() {
        if (!lyrics.isEmpty()) return lyrics;
        if (song.isEmpty()) return "";
        return artist.isEmpty() ? song : song + " · " + artist;
    }

    /** 多行文本，用于设置页与日志。 */
    String text() {
        StringBuilder text = new StringBuilder();
        if (!song.isEmpty()) text.append(song);
        if (!artist.isEmpty()) {
            if (text.length() > 0) text.append(" · ");
            text.append(artist);
        }
        if (!album.isEmpty()) {
            if (text.length() > 0) text.append('\n');
            text.append(album);
        }
        if (!lyrics.isEmpty()) {
            if (text.length() > 0) text.append('\n');
            text.append(lyrics);
        }
        if (playTime >= 0 && duration > 0) {
            if (text.length() > 0) text.append('\n');
            text.append(playTime / 1000).append("s / ").append(duration / 1000).append('s');
        }
        return text.length() == 0 ? "等待盒子上报媒体信息" : text.toString();
    }

    /** 空串/非字符串视为「未更新」，保留旧值。 */
    private static String string(JSONObject data, String key, String previous) {
        if (!data.has(key)) return previous;
        Object value = data.opt(key);
        if (!(value instanceof String)) return previous;
        String text = ((String) value).trim();
        return text.isEmpty() ? previous : text;
    }

    /** {@code -1}、非数字、超出 int 范围都视为「未更新」，保留旧值。 */
    private static int number(JSONObject data, String key, int previous) {
        if (!data.has(key)) return previous;
        Object value = data.opt(key);
        double parsed;
        if (value instanceof Number) {
            parsed = ((Number) value).doubleValue();
        } else if (value instanceof String) {
            try {
                parsed = Double.parseDouble(((String) value).trim());
            } catch (NumberFormatException exception) {
                return previous;
            }
        } else {
            return previous;
        }
        if (Double.isNaN(parsed) || parsed < -1 || parsed > Integer.MAX_VALUE
                || parsed != Math.rint(parsed)) {
            return previous;
        }
        return parsed == -1 ? previous : (int) parsed;
    }
}
