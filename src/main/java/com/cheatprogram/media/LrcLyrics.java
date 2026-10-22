package com.cheatprogram.media;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Immutable LRC, enhanced-LRC and SRT/VTT timeline. No game, network or file dependencies. */
public final class LrcLyrics {
	private static final Pattern STAMP = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]");
	private static final Pattern WORD = Pattern.compile("<(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?>");
	private static final Pattern YRC_LINE=Pattern.compile("^\\[(\\d{1,10}),(\\d{1,10})](.*)$"),YRC_WORD=Pattern.compile("\\((\\d{1,10}),(\\d{1,10}),[^)]*\\)");
	private static final Pattern OFFSET = Pattern.compile("(?im)^\\s*\\[offset:([+-]?\\d+)]\\s*$");
	private static final Pattern CUE = Pattern.compile("(?m)^(?:(\\d{1,3}):)?(\\d{2}):(\\d{2})[,.](\\d{3})\\s*-->\\s*(?:(\\d{1,3}):)?(\\d{2}):(\\d{2})[,.](\\d{3})[^\\r\\n]*\\R");
	public record Word(double start, double end, String text, int startChar, int endChar) { }
	public record Line(double start, double end, String text, String translation, List<Word> words) {
		public Line { words = List.copyOf(words); }
		/** Only a precise native unit that is audible now; gaps and coarse phrases stay empty. */
		public Word activeUnit(double seconds) {
			if(!Double.isFinite(seconds)||seconds<start||seconds>=end)return null;
			Word active=null;
			for(Word word:words){
				if(!Double.isFinite(word.start())||!Double.isFinite(word.end())||word.start()<start||word.end()>end||word.end()<=word.start()
						||word.startChar()<0||word.endChar()>text.length()||word.endChar()<=word.startChar()
						||LyricText.boundary(text,word.startChar())!=word.startChar()||LyricText.boundary(text,word.endChar())!=word.endChar())continue;
				String unit=text.substring(word.startChar(),word.endChar());
				if(seconds>=word.start()&&seconds<word.end()&&LyricText.singleUnit(unit)){
					// Two overlapping native units do not establish a unique current word.
					if(active!=null)return null;active=word;
				}
			}
			return active;
		}
		public String currentUnit(double seconds){Word word=activeUnit(seconds);return word==null?"":text.substring(word.startChar(),word.endChar()).strip();}
		/** Fraction of visible characters sung; uses real word timing when the file supplies it. */
		public double progress(double seconds) {
			if (text.isEmpty() || seconds <= start) return 0;
			if (seconds >= end) return 1;
			if (words.isEmpty()) return clamp((seconds - start) / Math.max(.001, end - start));
			double chars = 0;
			for (Word word : words) {
				if (seconds >= word.end()) chars = Math.max(chars, word.endChar());
				else if (seconds >= word.start()) chars = Math.max(chars, word.startChar()
						+ (word.endChar() - word.startChar()) * clamp((seconds - word.start()) / Math.max(.001, word.end() - word.start())));
			}
			return clamp(chars / text.length());
		}
		/** Native word onsets reveal that word. Coarse phrases wait for completion: never guess a character clock. */
		public String visiblePrefix(double seconds){
			if(!Double.isFinite(seconds)||seconds<start)return "";if(seconds>=end)return text;
			int visible=0;for(Word word:words){
				if(!Double.isFinite(word.start())||!Double.isFinite(word.end())||word.start()<start||word.end()<word.start()||word.startChar()<0||word.endChar()>text.length()||word.endChar()<=word.startChar()
						||LyricText.boundary(text,word.startChar())!=word.startChar()||LyricText.boundary(text,word.endChar())!=word.endChar())break;
				if(word.startChar()>visible&&!LyricText.untimedGap(text.substring(visible,word.startChar())))break;
				// Later onsets must not pull an unfinished earlier phrase into a contiguous prefix.
				if(!(seconds>=word.end()||seconds>=word.start()&&LyricText.singleUnit(text.substring(word.startChar(),word.endChar()))))break;
				visible=Math.max(visible,word.endChar());
			}
			if(visible<=0)return "";if(visible>=text.length())return text;
			return LyricText.prefix(text,visible);
		}
		/** Distinguishes a true word clock from coarse native segments and line-only lyrics. */
		public String timing(){return words.isEmpty()?"lineTiming":words.stream().allMatch(w->LyricText.singleUnit(w.text()))?"wordTiming":"segmentTiming";}
	}
	private record Raw(double start, double explicitEnd, String text, List<Word> words) { }
	private final List<Line> lines;
	private final boolean synced;
	private final String plainText;
	private final boolean inferredLastEnd;
	private LrcLyrics(List<Line> lines, boolean synced, String plainText) {
		this(lines,synced,plainText,false);
	}
	private LrcLyrics(List<Line> lines, boolean synced, String plainText,boolean inferredLastEnd) {
		this.lines = List.copyOf(lines); this.synced = synced; this.plainText = plainText;
		this.inferredLastEnd=inferredLastEnd;
	}
	public List<Line> lines() { return lines; }
	public boolean synced() { return synced; }
	public String plainText() { return plainText; }
	public boolean isEmpty() { return lines.isEmpty() && plainText.isBlank(); }
	public boolean inferredLastEnd(){return inferredLastEnd;}
	/** Plain LRC supplies no final cue end; the observed track duration takes precedence. */
	public double end(int index,double duration){Line line=lines.get(index);return inferredLastEnd&&index==lines.size()-1&&duration>line.start()?duration:line.end();}
	public String visiblePrefix(int index,double seconds,double duration){Line line=lines.get(index);double end=end(index,duration);if(seconds>=end)return line.text();return line.visiblePrefix(Math.min(seconds,Math.nextDown(line.end())));}
	public String currentUnit(int index,double seconds,double duration){return index<0||index>=lines.size()||Double.isFinite(duration)&&duration>0&&seconds>=duration||seconds>=end(index,duration)?"":lines.get(index).currentUnit(seconds);}
	/** The observed track end replaces an inferred final line end, with an exclusive end boundary. */
	public int lineAt(double seconds,double duration){
		if(!Double.isFinite(seconds)||Double.isFinite(duration)&&duration>0&&seconds>=duration)return -1;
		if(inferredLastEnd&&!lines.isEmpty()){
			int last=lines.size()-1;
			if(seconds>=lines.get(last).start())return seconds<end(last,duration)?last:-1;
		}
		return lineAt(seconds);
	}
	/** -1 before the first cue or within an explicit subtitle gap. */
	public int lineAt(double seconds) {
		if (!synced || !Double.isFinite(seconds)) return -1;
		int lo = 0, hi = lines.size() - 1, found = -1;
		while (lo <= hi) { int mid = (lo + hi) >>> 1; if (lines.get(mid).start() <= seconds) { found = mid; lo = mid + 1; } else hi = mid - 1; }
		return found >= 0 && seconds < lines.get(found).end() ? found : -1;
	}
	public static LrcLyrics parse(String input) {
		String source = Objects.requireNonNullElse(input, "").replace("\uFEFF", "");
		if (source.length() > 2_000_000) throw new IllegalArgumentException("Lyrics exceed 2 MB");
		if (CUE.matcher(source).find()) return parseCues(source);
		for(String line:source.split("\\R",-1))if(YRC_LINE.matcher(line).matches())return parseYrc(source);
		double offset = 0;
		Matcher offsetMatcher = OFFSET.matcher(source);
		while (offsetMatcher.find()) { try { offset = Math.max(-3_600_000, Math.min(3_600_000, Long.parseLong(offsetMatcher.group(1)))) / 1000.0; } catch (NumberFormatException ignored) { } }
		List<Raw> raw = new ArrayList<>();
		for (String inputLine : source.split("\\R", -1)) {
			Matcher stamps = STAMP.matcher(inputLine);
			List<Double> starts = new ArrayList<>(); int content = 0;
			while (stamps.find()) {
				// Timestamp headers only; a bracket inside the lyric is ordinary text.
				if (!inputLine.substring(content, stamps.start()).isBlank()) break;
				starts.add(timestamp(stamps.group(1), stamps.group(2), stamps.group(3)) - offset); content = stamps.end();
			}
			if (starts.isEmpty()) continue;
			String lyric = inputLine.substring(content).strip();
			Matcher words = WORD.matcher(lyric); List<Double> wordTimes = new ArrayList<>(); List<Integer> positions = new ArrayList<>();
			StringBuilder text = new StringBuilder(); int last = 0;
			while (words.find()) { text.append(lyric, last, words.start()); positions.add(text.length()); wordTimes.add(timestamp(words.group(1), words.group(2), words.group(3)) - offset); last = words.end(); }
			text.append(lyric.substring(last));
			for (double start : starts) {
				List<Word> segments = new ArrayList<>(); double aliasShift = start - starts.get(0);
				for (int i = 0; i < wordTimes.size(); i++) {
					int from = positions.get(i), to = i + 1 < positions.size() ? positions.get(i + 1) : text.length();
					if (to <= from) continue;
					segments.add(new Word(wordTimes.get(i) + aliasShift, i + 1 < wordTimes.size() ? wordTimes.get(i + 1) + aliasShift : Double.POSITIVE_INFINITY,
							text.substring(from, to), from, to));
				}
				raw.add(new Raw(start, Double.NaN, text.toString(), segments));
				if (raw.size() > 20_000) throw new IllegalArgumentException("Too many lyric cues");
			}
		}
		return finish(raw, source);
	}
	private static LrcLyrics parseYrc(String source){List<Raw> cues=new ArrayList<>();for(String value:source.split("\\R",-1)){Matcher line=YRC_LINE.matcher(value);if(!line.matches())continue;double start=Long.parseLong(line.group(1))/1000.0,end=start+Long.parseLong(line.group(2))/1000.0;if(end<=start||end>86400)continue;
		String encoded=line.group(3);Matcher word=YRC_WORD.matcher(encoded);List<Integer> positions=new ArrayList<>();List<Double> starts=new ArrayList<>(),ends=new ArrayList<>();StringBuilder text=new StringBuilder();int last=0;
		while(word.find()){text.append(encoded,last,word.start());positions.add(text.length());starts.add(Long.parseLong(word.group(1))/1000.0);ends.add((Long.parseLong(word.group(1))+Long.parseLong(word.group(2)))/1000.0);last=word.end();}text.append(encoded.substring(last));List<Word> words=new ArrayList<>();
		for(int i=0;i<positions.size();i++){int from=positions.get(i),to=i+1<positions.size()?positions.get(i+1):text.length();double ws=Math.max(start,starts.get(i)),we=Math.min(end,ends.get(i));if(to>from&&we>=ws)words.add(new Word(ws,we,text.substring(from,to),from,to));}
		if(!text.toString().isBlank())cues.add(new Raw(start,end,text.toString(),words));if(cues.size()>20000)throw new IllegalArgumentException("Too many lyric cues");}return finish(cues,source);
	}
	public LrcLyrics withTranslation(LrcLyrics translated) {
		if (translated == null || !translated.synced || !synced) return this;
		List<Line> merged = new ArrayList<>(); int at = 0;
		for (Line line : lines) {
			while (at + 1 < translated.lines.size() && translated.lines.get(at).start() < line.start() - .35) at++;
			String text = line.translation();
			if (at < translated.lines.size() && Math.abs(translated.lines.get(at).start() - line.start()) <= .35) text = translated.lines.get(at).text();
			merged.add(new Line(line.start(), line.end(), line.text(), text, line.words()));
		}
		return new LrcLyrics(merged, true, plainText,inferredLastEnd);
	}
	private static LrcLyrics parseCues(String source) {
		Matcher cue = CUE.matcher(source); List<Raw> lines = new ArrayList<>();
		while (cue.find()) {
			double start = cueTime(cue, 1), end = cueTime(cue, 5); int from = cue.end();
			int next = source.indexOf("\n\n", from), crnext = source.indexOf("\r\n\r\n", from);
			if (next < 0 || crnext >= 0 && crnext < next) next = crnext;
			String text = source.substring(from, next < 0 ? source.length() : next).strip().replaceAll("<[^>]*>", "").replaceAll("\\R", "\n");
			lines.add(new Raw(start, Math.max(start + .001, end), text, List.of()));
			if (lines.size() > 20_000) throw new IllegalArgumentException("Too many subtitle cues");
		}
		return finish(lines, source);
	}
	private static double cueTime(Matcher m, int at) { return (m.group(at) == null ? 0 : Integer.parseInt(m.group(at)) * 3600) + Integer.parseInt(m.group(at + 1)) * 60 + Integer.parseInt(m.group(at + 2)) + Integer.parseInt(m.group(at + 3)) / 1000.0; }
	private static LrcLyrics finish(List<Raw> source, String original) {
		if (source.isEmpty()) return new LrcLyrics(List.of(), false, original.strip());
		source.sort(Comparator.comparingDouble(Raw::start)); List<Line> lines = new ArrayList<>();
		for (int i = 0; i < source.size();) {
			Raw raw = source.get(i++); String translation = "";
			while (i < source.size() && Math.abs(source.get(i).start() - raw.start()) < .001) {
				String alternate = source.get(i++).text();
				if (!alternate.isBlank() && !alternate.equals(raw.text())) translation = translation.isEmpty() ? alternate : translation + "\n" + alternate;
			}
			double end = Double.isFinite(raw.explicitEnd()) ? raw.explicitEnd() : i < source.size() ? source.get(i).start() : raw.start() + 8;
			end = Math.max(raw.start() + .001, end); List<Word> words = new ArrayList<>();
			for (Word word : raw.words()) words.add(new Word(Math.max(raw.start(), word.start()), Math.min(end, word.end()), word.text(), word.startChar(), word.endChar()));
			lines.add(new Line(raw.start(), end, raw.text(), translation, words));
		}
		return new LrcLyrics(lines, true, String.join("\n", lines.stream().map(Line::text).toList()),!Double.isFinite(source.getLast().explicitEnd()));
	}
	private static double timestamp(String minutes, String seconds, String fraction) { return Integer.parseInt(minutes) * 60 + Integer.parseInt(seconds) + (fraction == null ? 0 : Integer.parseInt(fraction) / Math.pow(10, fraction.length())); }
	private static double clamp(double value) { return Math.max(0, Math.min(1, value)); }
}
