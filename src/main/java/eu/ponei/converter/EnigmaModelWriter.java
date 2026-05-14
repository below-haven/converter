package eu.ponei.converter;

import static eu.ponei.converter.MappingNamespaces.INTERMEDIARY;
import static eu.ponei.converter.MappingNamespaces.NAMED;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import net.fabricmc.mappingio.MappedElementKind;
import net.fabricmc.mappingio.MappingWriter;
import net.fabricmc.mappingio.format.MappingFormat;

final class EnigmaModelWriter {
	void writeAtomically(MappingModel model, Path output) throws ConversionException {
		Path absoluteOutput = output.toAbsolutePath();
		Path parent = absoluteOutput.getParent();

		try {
			if (parent != null) {
				Files.createDirectories(parent);
			}
		} catch (IOException e) {
			throw new ConversionException("Failed to create output directory: " + output, e);
		}

		Path temp = createTempFile(parent, absoluteOutput);

		try {
			writeModel(model, temp);
			replaceOutput(temp, absoluteOutput);
		} catch (IOException e) {
			deleteTemp(temp);
			throw new ConversionException("Failed to write Enigma file: " + output, e);
		} catch (RuntimeException e) {
			deleteTemp(temp);
			throw e;
		}
	}

	private Path createTempFile(Path parent, Path absoluteOutput) throws ConversionException {
		try {
			return Files.createTempFile(parent, absoluteOutput.getFileName().toString(), ".tmp");
		} catch (IOException e) {
			throw new ConversionException("Failed to create temporary output file for " + absoluteOutput, e);
		}
	}

	private void replaceOutput(Path temp, Path output) throws IOException {
		try {
			Files.move(temp, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private void deleteTemp(Path temp) {
		try {
			Files.deleteIfExists(temp);
		} catch (IOException ignored) {
		}
	}

	private void writeModel(MappingModel model, Path output) throws IOException {
		try (MappingWriter writer = Objects.requireNonNull(MappingWriter.create(output, MappingFormat.ENIGMA_FILE))) {
			if (writer.visitHeader()) {
				writer.visitNamespaces(INTERMEDIARY, List.of(NAMED));
			}

			if (writer.visitContent()) {
				for (MappingModel.ClassEntry classEntry : classesBySourceName(model)) {
					writeClass(writer, classEntry);
				}
			}

			writer.visitEnd();
		}
	}

	private void writeClass(MappingWriter writer, MappingModel.ClassEntry classEntry) throws IOException {
		if (!writer.visitClass(classEntry.intermediaryName())) {
			return;
		}

		writeDstName(writer, MappedElementKind.CLASS, classEntry.namedName());
		if (!writer.visitElementContent(MappedElementKind.CLASS)) {
			return;
		}

		writeComment(writer, MappedElementKind.CLASS, classEntry.comment());

		for (MappingModel.FieldEntry fieldEntry : membersBySourceNameAndDescriptor(classEntry.fields())) {
			writeField(writer, fieldEntry);
		}

		for (MappingModel.MethodEntry methodEntry : membersBySourceNameAndDescriptor(classEntry.methods())) {
			writeMethod(writer, methodEntry);
		}
	}

	private void writeField(MappingWriter writer, MappingModel.FieldEntry fieldEntry) throws IOException {
		if (!fieldEntry.shouldWrite()) {
			return;
		}

		if (!writer.visitField(fieldEntry.intermediaryName(), fieldEntry.intermediaryDesc())) {
			return;
		}

		writeDstName(writer, MappedElementKind.FIELD, fieldEntry.namedName());
		if (writer.visitElementContent(MappedElementKind.FIELD)) {
			writeComment(writer, MappedElementKind.FIELD, fieldEntry.comment());
		}
	}

	private void writeMethod(MappingWriter writer, MappingModel.MethodEntry methodEntry) throws IOException {
		if (!writer.visitMethod(methodEntry.intermediaryName(), methodEntry.intermediaryDesc())) {
			return;
		}

		writeDstName(writer, MappedElementKind.METHOD, methodEntry.namedName());
		if (!writer.visitElementContent(MappedElementKind.METHOD)) {
			return;
		}

		writeComment(writer, MappedElementKind.METHOD, methodEntry.comment());

		for (MappingModel.ArgEntry argEntry : argsByIndex(methodEntry)) {
			writeArg(writer, argEntry);
		}
	}

	private void writeArg(MappingWriter writer, MappingModel.ArgEntry argEntry) throws IOException {
		if (!writer.visitMethodArg(-1, argEntry.lvIndex(), null)) {
			return;
		}

		writeDstName(writer, MappedElementKind.METHOD_ARG, argEntry.namedName());
		if (writer.visitElementContent(MappedElementKind.METHOD_ARG)) {
			writeComment(writer, MappedElementKind.METHOD_ARG, argEntry.comment());
		}
	}

	private void writeDstName(MappingWriter writer, MappedElementKind kind, String name) throws IOException {
		if (name != null && !name.isEmpty()) {
			writer.visitDstName(kind, 0, name);
		}
	}

	private void writeComment(MappingWriter writer, MappedElementKind kind, String comment) throws IOException {
		if (comment != null && !comment.isEmpty()) {
			writer.visitComment(kind, comment);
		}
	}

	private List<MappingModel.ClassEntry> classesBySourceName(MappingModel model) {
		return model.classes().stream()
				.sorted(Comparator.comparing(MappingModel.ClassEntry::intermediaryName, this::compareNaturally))
				.toList();
	}

	private <T extends MappingModel.MemberEntry> List<T> membersBySourceNameAndDescriptor(List<T> members) {
		return members.stream()
				.sorted(Comparator
						.comparing(MappingModel.MemberEntry::intermediaryName, this::compareNaturally)
						.thenComparing(MappingModel.MemberEntry::intermediaryDesc, this::compareNaturally))
				.toList();
	}

	private List<MappingModel.ArgEntry> argsByIndex(MappingModel.MethodEntry methodEntry) {
		return methodEntry.args().stream()
				.sorted(Comparator.comparingInt(MappingModel.ArgEntry::lvIndex))
				.toList();
	}

	private int compareNaturally(String left, String right) {
		int leftIndex = 0;
		int rightIndex = 0;

		while (leftIndex < left.length() && rightIndex < right.length()) {
			char leftChar = left.charAt(leftIndex);
			char rightChar = right.charAt(rightIndex);

			if (Character.isDigit(leftChar) && Character.isDigit(rightChar)) {
				int result = compareNumberRuns(left, leftIndex, right, rightIndex);
				if (result != 0) {
					return result;
				}

				leftIndex = endOfNumberRun(left, leftIndex);
				rightIndex = endOfNumberRun(right, rightIndex);
				continue;
			}

			int result = Character.compare(leftChar, rightChar);
			if (result != 0) {
				return result;
			}

			leftIndex++;
			rightIndex++;
		}

		return Integer.compare(left.length(), right.length());
	}

	private int compareNumberRuns(String left, int leftStart, String right, int rightStart) {
		int leftEnd = endOfNumberRun(left, leftStart);
		int rightEnd = endOfNumberRun(right, rightStart);
		int leftSignificant = firstSignificantDigit(left, leftStart, leftEnd);
		int rightSignificant = firstSignificantDigit(right, rightStart, rightEnd);
		int leftLength = leftEnd - leftSignificant;
		int rightLength = rightEnd - rightSignificant;

		if (leftLength != rightLength) {
			return Integer.compare(leftLength, rightLength);
		}

		for (int i = 0; i < leftLength; i++) {
			int result = Character.compare(left.charAt(leftSignificant + i), right.charAt(rightSignificant + i));
			if (result != 0) {
				return result;
			}
		}

		return Integer.compare(leftEnd - leftStart, rightEnd - rightStart);
	}

	private int endOfNumberRun(String value, int start) {
		int index = start;
		while (index < value.length() && Character.isDigit(value.charAt(index))) {
			index++;
		}

		return index;
	}

	private int firstSignificantDigit(String value, int start, int end) {
		int index = start;
		while (index < end - 1 && value.charAt(index) == '0') {
			index++;
		}

		return index;
	}
}
