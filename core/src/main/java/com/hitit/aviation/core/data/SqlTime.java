package com.hitit.aviation.core.data;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class SqlTime {
	private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
	private SqlTime() {}
	public static String toDb(LocalDateTime t) { return t == null ? null : t.format(F);}
	public static LocalDateTime fromDb(String s) { return (s == null || s.isBlank()) ? null : LocalDateTime.parse(s, F);}
}
