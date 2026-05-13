package eu.ponei.converter;

import static net.fabricmc.mappingio.tree.MappingTreeView.NULL_NAMESPACE_ID;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import net.fabricmc.mappingio.MappedElementKind;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.MappingWriter;
import net.fabricmc.mappingio.format.MappingFormat;
import net.fabricmc.mappingio.format.enigma.EnigmaFileReader;
import net.fabricmc.mappingio.tree.MappingTreeView;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

final class TinyToEnigmaConverter {
	private static final String OFFICIAL = "official";
	private static final String INTERMEDIARY = "intermediary";
	private static final String NAMED = "named";

	void convert(Path input, Path output) throws ConversionException {
		if (!Files.isRegularFile(input)) {
			throw new ConversionException("Input Tiny file does not exist: " + input);
		}

		MappingModel current = readTiny(input);

		if (Files.exists(output)) {
			mergeExisting(current, readEnigma(output));
		}

		writeAtomically(current, output);
	}

	private MappingModel readTiny(Path input) throws ConversionException {
		MemoryMappingTree tree = new MemoryMappingTree();

		try {
			MappingReader.read(input, tree);
		} catch (IOException e) {
			throw new ConversionException("Failed to read Tiny file: " + input, e);
		}

		validateTinyNamespaces(tree);
		return readModel(tree, OFFICIAL, INTERMEDIARY, false);
	}

	private MappingModel readEnigma(Path input) throws ConversionException {
		MemoryMappingTree tree = new MemoryMappingTree();

		try (Reader reader = Files.newBufferedReader(input)) {
			EnigmaFileReader.read(reader, INTERMEDIARY, NAMED, tree);
		} catch (IOException e) {
			throw new ConversionException("Failed to read existing Enigma file: " + input, e);
		}

		return readModel(tree, NAMED, INTERMEDIARY, true);
	}

	private void validateTinyNamespaces(MappingTreeView tree) throws ConversionException {
		Set<String> namespaces = new LinkedHashSet<>();
		namespaces.add(tree.getSrcNamespace());
		namespaces.addAll(tree.getDstNamespaces());

		if (namespaces.size() != 2 || !namespaces.contains(OFFICIAL) || !namespaces.contains(INTERMEDIARY)) {
			throw new ConversionException("Tiny file must contain exactly the namespaces official and intermediary");
		}
	}

	private MappingModel readModel(
			MappingTreeView tree,
			String namedNamespace,
			String intermediaryNamespace,
			boolean defaultMissingNamedToIntermediary) throws ConversionException {
		int namedNs = namespaceId(tree, namedNamespace);
		int intermediaryNs = namespaceId(tree, intermediaryNamespace);
		MappingModel model = new MappingModel();

		for (MappingTreeView.ClassMappingView sourceClass : tree.getClasses()) {
			String intermediaryClassName = requireName(sourceClass, intermediaryNs, "class");
			String namedClassName = nameOrDefault(sourceClass, namedNs, intermediaryNs, "class", defaultMissingNamedToIntermediary);
			MappingModel.ClassEntry classEntry = new MappingModel.ClassEntry(
					intermediaryClassName,
					namedClassName,
					sourceClass.getComment());

			for (MappingTreeView.FieldMappingView sourceField : sourceClass.getFields()) {
				classEntry.addField(new MappingModel.FieldEntry(
						requireName(sourceField, intermediaryNs, "field in " + intermediaryClassName),
						requireDesc(sourceField, intermediaryNs, "field " + sourceField.getSrcName() + " in " + intermediaryClassName),
						nameOrDefault(sourceField, namedNs, intermediaryNs, "field in " + intermediaryClassName, defaultMissingNamedToIntermediary),
						sourceField.getComment()));
			}

			for (MappingTreeView.MethodMappingView sourceMethod : sourceClass.getMethods()) {
				MappingModel.MethodEntry methodEntry = new MappingModel.MethodEntry(
						requireName(sourceMethod, intermediaryNs, "method in " + intermediaryClassName),
						requireDesc(sourceMethod, intermediaryNs, "method " + sourceMethod.getSrcName() + " in " + intermediaryClassName),
						nameOrDefault(sourceMethod, namedNs, intermediaryNs, "method in " + intermediaryClassName, defaultMissingNamedToIntermediary),
						sourceMethod.getComment());

				for (MappingTreeView.MethodArgMappingView sourceArg : sourceMethod.getArgs()) {
					if (sourceArg.getLvIndex() < 0) {
						continue;
					}

					String namedArg = sourceArg.getName(namedNs);
					if (namedArg == null || namedArg.isEmpty()) {
						namedArg = sourceArg.getName(intermediaryNs);
					}

					if (namedArg != null && !namedArg.isEmpty()) {
						methodEntry.addArg(new MappingModel.ArgEntry(sourceArg.getLvIndex(), namedArg, sourceArg.getComment()));
					}
				}

				classEntry.addMethod(methodEntry);
			}

			model.addClass(classEntry);
		}

		return model;
	}

	private int namespaceId(MappingTreeView tree, String namespace) throws ConversionException {
		int id = tree.getNamespaceId(namespace);
		if (id == NULL_NAMESPACE_ID) {
			throw new ConversionException("Mapping tree is missing namespace: " + namespace);
		}

		return id;
	}

	private String requireName(MappingTreeView.ElementMappingView element, int namespace, String description) throws ConversionException {
		String name = element.getName(namespace);
		if (name == null || name.isEmpty()) {
			throw new ConversionException("Missing " + description + " name in required namespace");
		}

		return name;
	}

	private String nameOrDefault(
			MappingTreeView.ElementMappingView element,
			int namedNamespace,
			int intermediaryNamespace,
			String description,
			boolean defaultMissingNamedToIntermediary) throws ConversionException {
		String name = element.getName(namedNamespace);
		if (name != null && !name.isEmpty()) {
			return name;
		}

		if (defaultMissingNamedToIntermediary) {
			return requireName(element, intermediaryNamespace, description);
		}

		throw new ConversionException("Missing " + description + " name in required namespace");
	}

	private String requireDesc(MappingTreeView.MemberMappingView element, int namespace, String description) throws ConversionException {
		String desc = element.getDesc(namespace);
		if (desc == null || desc.isEmpty()) {
			throw new ConversionException("Missing descriptor for " + description);
		}

		return desc;
	}

	private void mergeExisting(MappingModel current, MappingModel existing) throws ConversionException {
		for (MappingModel.ClassEntry currentClass : current.classes()) {
			MappingModel.ClassEntry existingClass = existing.classByIntermediary(currentClass.intermediaryName());
			if (existingClass == null) {
				continue;
			}

			currentClass.setNamedName(existingClass.namedName());
			currentClass.setComment(existingClass.comment());
			mergeMembers(currentClass.intermediaryName(), "field", currentClass.fields(), existingClass);
			mergeMembers(currentClass.intermediaryName(), "method", currentClass.methods(), existingClass);
		}
	}

	private <T extends MappingModel.MemberEntry> void mergeMembers(
			String owner,
			String kind,
			List<T> currentMembers,
			MappingModel.ClassEntry existingClass) throws ConversionException {
		Set<T> exactMatched = new LinkedHashSet<>();
		Set<MappingModel.MemberEntry> usedExisting = new LinkedHashSet<>();

		for (T current : currentMembers) {
			MappingModel.MemberEntry existing = existingMember(kind, existingClass, current.key());
			if (existing != null) {
				copyMember(current, existing);
				exactMatched.add(current);
				usedExisting.add(existing);
			}
		}

		Set<T> migrated = new LinkedHashSet<>();

		for (T current : currentMembers) {
			if (exactMatched.contains(current) || migrated.contains(current)) {
				continue;
			}

			List<T> currentCandidates = unmatchedByName(currentMembers, exactMatched, migrated, current.intermediaryName());
			List<MappingModel.MemberEntry> existingCandidates = existingByName(kind, existingClass, usedExisting, current.intermediaryName());

			if (existingCandidates.isEmpty()) {
				continue;
			}

			if (currentCandidates.size() != 1 || existingCandidates.size() != 1) {
				throw new ConversionException("Ambiguous " + kind + " descriptor change for "
						+ owner + "." + current.intermediaryName());
			}

			T currentCandidate = currentCandidates.getFirst();
			MappingModel.MemberEntry existingCandidate = existingCandidates.getFirst();
			copyMember(currentCandidate, existingCandidate);
			migrated.add(currentCandidate);
			usedExisting.add(existingCandidate);
		}
	}

	private MappingModel.MemberEntry existingMember(String kind, MappingModel.ClassEntry existingClass, MappingModel.MemberKey key) {
		if (kind.equals("field")) {
			return existingClass.fieldByExactKey(key);
		}

		return existingClass.methodByExactKey(key);
	}

	private <T extends MappingModel.MemberEntry> List<T> unmatchedByName(
			List<T> currentMembers,
			Set<T> exactMatched,
			Set<T> migrated,
			String intermediaryName) {
		List<T> candidates = new ArrayList<>();

		for (T member : currentMembers) {
			if (!exactMatched.contains(member)
					&& !migrated.contains(member)
					&& member.intermediaryName().equals(intermediaryName)) {
				candidates.add(member);
			}
		}

		return candidates;
	}

	private List<MappingModel.MemberEntry> existingByName(
			String kind,
			MappingModel.ClassEntry existingClass,
			Set<MappingModel.MemberEntry> usedExisting,
			String intermediaryName) {
		List<? extends MappingModel.MemberEntry> existingMembers = kind.equals("field")
				? existingClass.fields()
				: existingClass.methods();
		List<MappingModel.MemberEntry> candidates = new ArrayList<>();

		for (MappingModel.MemberEntry member : existingMembers) {
			if (!usedExisting.contains(member) && member.intermediaryName().equals(intermediaryName)) {
				candidates.add(member);
			}
		}

		return candidates;
	}

	private void copyMember(MappingModel.MemberEntry current, MappingModel.MemberEntry existing) {
		current.setNamedName(existing.namedName());
		current.setComment(existing.comment());

		if (current instanceof MappingModel.MethodEntry currentMethod && existing instanceof MappingModel.MethodEntry existingMethod) {
			for (MappingModel.ArgEntry currentArg : currentMethod.args()) {
				MappingModel.ArgEntry existingArg = existingMethod.argByLvIndex(currentArg.lvIndex());
				if (existingArg != null) {
					currentArg.setNamedName(existingArg.namedName());
					currentArg.setComment(existingArg.comment());
				}
			}
		}
	}

	private void writeAtomically(MappingModel model, Path output) throws ConversionException {
		Path absoluteOutput = output.toAbsolutePath();
		Path parent = absoluteOutput.getParent();

		try {
			if (parent != null) {
				Files.createDirectories(parent);
			}
		} catch (IOException e) {
			throw new ConversionException("Failed to create output directory: " + output, e);
		}

		Path temp;
		try {
			temp = Files.createTempFile(parent, absoluteOutput.getFileName().toString(), ".tmp");
		} catch (IOException e) {
			throw new ConversionException("Failed to create temporary output file for " + output, e);
		}

		try {
			writeModel(model, temp);
			try {
				Files.move(temp, absoluteOutput, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(temp, absoluteOutput, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			deleteTemp(temp);
			throw new ConversionException("Failed to write Enigma file: " + output, e);
		} catch (RuntimeException e) {
			deleteTemp(temp);
			throw e;
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
				for (MappingModel.ClassEntry classEntry : model.classes()) {
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

		writer.visitDstName(MappedElementKind.CLASS, 0, classEntry.namedName());
		if (!writer.visitElementContent(MappedElementKind.CLASS)) {
			return;
		}

		writeComment(writer, MappedElementKind.CLASS, classEntry.comment());

		for (MappingModel.FieldEntry fieldEntry : classEntry.fields()) {
			writeField(writer, fieldEntry);
		}

		for (MappingModel.MethodEntry methodEntry : classEntry.methods()) {
			writeMethod(writer, methodEntry);
		}
	}

	private void writeField(MappingWriter writer, MappingModel.FieldEntry fieldEntry) throws IOException {
		if (!writer.visitField(fieldEntry.intermediaryName(), fieldEntry.intermediaryDesc())) {
			return;
		}

		writer.visitDstName(MappedElementKind.FIELD, 0, fieldEntry.namedName());
		if (writer.visitElementContent(MappedElementKind.FIELD)) {
			writeComment(writer, MappedElementKind.FIELD, fieldEntry.comment());
		}
	}

	private void writeMethod(MappingWriter writer, MappingModel.MethodEntry methodEntry) throws IOException {
		if (!writer.visitMethod(methodEntry.intermediaryName(), methodEntry.intermediaryDesc())) {
			return;
		}

		writer.visitDstName(MappedElementKind.METHOD, 0, methodEntry.namedName());
		if (!writer.visitElementContent(MappedElementKind.METHOD)) {
			return;
		}

		writeComment(writer, MappedElementKind.METHOD, methodEntry.comment());

		for (MappingModel.ArgEntry argEntry : methodEntry.args()) {
			writeArg(writer, argEntry);
		}
	}

	private void writeArg(MappingWriter writer, MappingModel.ArgEntry argEntry) throws IOException {
		if (!writer.visitMethodArg(-1, argEntry.lvIndex(), null)) {
			return;
		}

		writer.visitDstName(MappedElementKind.METHOD_ARG, 0, argEntry.namedName());
		if (writer.visitElementContent(MappedElementKind.METHOD_ARG)) {
			writeComment(writer, MappedElementKind.METHOD_ARG, argEntry.comment());
		}
	}

	private void writeComment(MappingWriter writer, MappedElementKind kind, String comment) throws IOException {
		if (comment != null && !comment.isEmpty()) {
			writer.visitComment(kind, comment);
		}
	}
}
