package eu.ponei.converter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class MappingModel {
	private final List<ClassEntry> classes = new ArrayList<>();
	private final Map<String, ClassEntry> classesByIntermediary = new LinkedHashMap<>();

	List<ClassEntry> classes() {
		return classes;
	}

	void addClass(ClassEntry entry) throws ConversionException {
		ClassEntry previous = classesByIntermediary.putIfAbsent(entry.intermediaryName(), entry);
		if (previous != null) {
			throw new ConversionException("Duplicate class mapping for " + entry.intermediaryName());
		}

		classes.add(entry);
	}

	ClassEntry classByIntermediary(String intermediaryName) {
		return classesByIntermediary.get(intermediaryName);
	}

	static final class ClassEntry {
		private final String intermediaryName;
		private String namedName;
		private String comment;
		private final List<FieldEntry> fields = new ArrayList<>();
		private final List<MethodEntry> methods = new ArrayList<>();
		private final Map<MemberKey, FieldEntry> fieldsByExactKey = new LinkedHashMap<>();
		private final Map<MemberKey, MethodEntry> methodsByExactKey = new LinkedHashMap<>();

		ClassEntry(String intermediaryName, String namedName, String comment) {
			this.intermediaryName = intermediaryName;
			this.namedName = namedName;
			this.comment = comment;
		}

		String intermediaryName() {
			return intermediaryName;
		}

		String namedName() {
			return namedName;
		}

		void setNamedName(String namedName) {
			this.namedName = namedName;
		}

		String comment() {
			return comment;
		}

		void setComment(String comment) {
			this.comment = comment;
		}

		List<FieldEntry> fields() {
			return fields;
		}

		List<MethodEntry> methods() {
			return methods;
		}

		void addField(FieldEntry entry) throws ConversionException {
			MemberKey key = entry.key();
			FieldEntry previous = fieldsByExactKey.putIfAbsent(key, entry);
			if (previous != null) {
				throw new ConversionException("Duplicate field mapping for " + intermediaryName + "." + key);
			}

			fields.add(entry);
		}

		void addMethod(MethodEntry entry) throws ConversionException {
			MemberKey key = entry.key();
			MethodEntry previous = methodsByExactKey.putIfAbsent(key, entry);
			if (previous != null) {
				throw new ConversionException("Duplicate method mapping for " + intermediaryName + "." + key);
			}

			methods.add(entry);
		}

		FieldEntry fieldByExactKey(MemberKey key) {
			return fieldsByExactKey.get(key);
		}

		MethodEntry methodByExactKey(MemberKey key) {
			return methodsByExactKey.get(key);
		}
	}

	static sealed class MemberEntry permits FieldEntry, MethodEntry {
		private final String intermediaryName;
		private final String intermediaryDesc;
		private String namedName;
		private String comment;

		MemberEntry(String intermediaryName, String intermediaryDesc, String namedName, String comment) {
			this.intermediaryName = intermediaryName;
			this.intermediaryDesc = intermediaryDesc;
			this.namedName = namedName;
			this.comment = comment;
		}

		String intermediaryName() {
			return intermediaryName;
		}

		String intermediaryDesc() {
			return intermediaryDesc;
		}

		String namedName() {
			return namedName;
		}

		void setNamedName(String namedName) {
			this.namedName = namedName;
		}

		String comment() {
			return comment;
		}

		void setComment(String comment) {
			this.comment = comment;
		}

		MemberKey key() {
			return new MemberKey(intermediaryName, intermediaryDesc);
		}
	}

	static final class FieldEntry extends MemberEntry {
		private boolean writeWhenUnnamed;

		FieldEntry(String intermediaryName, String intermediaryDesc, String namedName, String comment) {
			this(intermediaryName, intermediaryDesc, namedName, comment, true);
		}

		FieldEntry(
				String intermediaryName,
				String intermediaryDesc,
				String namedName,
				String comment,
				boolean writeWhenUnnamed) {
			super(intermediaryName, intermediaryDesc, namedName, comment);
			this.writeWhenUnnamed = writeWhenUnnamed;
		}

		boolean shouldWrite() {
			return namedName() != null || writeWhenUnnamed;
		}

		void setWriteWhenUnnamed(boolean writeWhenUnnamed) {
			this.writeWhenUnnamed = writeWhenUnnamed;
		}
	}

	static final class MethodEntry extends MemberEntry {
		private final List<ArgEntry> args = new ArrayList<>();
		private final Map<Integer, ArgEntry> argsByLvIndex = new LinkedHashMap<>();

		MethodEntry(String intermediaryName, String intermediaryDesc, String namedName, String comment) {
			super(intermediaryName, intermediaryDesc, namedName, comment);
		}

		List<ArgEntry> args() {
			return args;
		}

		void addArg(ArgEntry entry) {
			ArgEntry previous = argsByLvIndex.putIfAbsent(entry.lvIndex(), entry);
			if (previous == null) {
				args.add(entry);
			}
		}

		ArgEntry argByLvIndex(int lvIndex) {
			return argsByLvIndex.get(lvIndex);
		}
	}

	static final class ArgEntry {
		private final int lvIndex;
		private String namedName;
		private String comment;

		ArgEntry(int lvIndex, String namedName, String comment) {
			this.lvIndex = lvIndex;
			this.namedName = namedName;
			this.comment = comment;
		}

		int lvIndex() {
			return lvIndex;
		}

		String namedName() {
			return namedName;
		}

		void setNamedName(String namedName) {
			this.namedName = namedName;
		}

		String comment() {
			return comment;
		}

		void setComment(String comment) {
			this.comment = comment;
		}
	}

	record MemberKey(String intermediaryName, String intermediaryDesc) {
		@Override
		public String toString() {
			return intermediaryName + " " + Objects.toString(intermediaryDesc, "<no-desc>");
		}
	}
}
