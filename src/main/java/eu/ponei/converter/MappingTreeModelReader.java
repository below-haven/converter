package eu.ponei.converter;

import static eu.ponei.converter.MappingNamespaces.INTERMEDIARY;
import static eu.ponei.converter.MappingNamespaces.NAMED;
import static eu.ponei.converter.MappingNamespaces.OFFICIAL;
import static net.fabricmc.mappingio.tree.MappingTreeView.NULL_NAMESPACE_ID;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.format.enigma.EnigmaFileReader;
import net.fabricmc.mappingio.tree.MappingTreeView;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

final class MappingTreeModelReader {
	MappingModel readTiny(Path input) throws ConversionException {
		return readTiny(input, false);
	}

	MappingModel readTiny(Path input, boolean addPackagePrefix) throws ConversionException {
		MemoryMappingTree tree = new MemoryMappingTree();

		try {
			MappingReader.read(input, tree);
		} catch (IOException e) {
			throw new ConversionException("Failed to read Tiny file: " + input, e);
		}

		validateTinyNamespaces(tree);
		return readTinyModel(tree, addPackagePrefix);
	}

	MappingModel readEnigma(Path input) throws ConversionException {
		MemoryMappingTree tree = new MemoryMappingTree();

		try (Reader reader = Files.newBufferedReader(input)) {
			EnigmaFileReader.read(reader, INTERMEDIARY, NAMED, tree);
		} catch (IOException e) {
			throw new ConversionException("Failed to read existing Enigma file: " + input, e);
		}

		return readExistingEnigmaModel(tree);
	}

	private void validateTinyNamespaces(MappingTreeView tree) throws ConversionException {
		Set<String> namespaces = new LinkedHashSet<>();
		namespaces.add(tree.getSrcNamespace());
		namespaces.addAll(tree.getDstNamespaces());

		if (namespaces.size() != 2 || !namespaces.contains(OFFICIAL) || !namespaces.contains(INTERMEDIARY)) {
			throw new ConversionException("Tiny file must contain exactly the namespaces official and intermediary");
		}
	}

	private MappingModel readTinyModel(MappingTreeView tree, boolean addPackagePrefix) throws ConversionException {
		int officialNs = namespaceId(tree, OFFICIAL);
		int intermediaryNs = namespaceId(tree, INTERMEDIARY);
		MappingModel model = new MappingModel();

		for (MappingTreeView.ClassMappingView sourceClass : tree.getClasses()) {
			model.addClass(readTinyClass(sourceClass, officialNs, intermediaryNs, addPackagePrefix));
		}

		return model;
	}

	private MappingModel.ClassEntry readTinyClass(
			MappingTreeView.ClassMappingView sourceClass,
			int officialNs,
			int intermediaryNs,
			boolean addPackagePrefix) throws ConversionException {
		String intermediaryClassName = requireName(sourceClass, intermediaryNs, "class");
		String officialClassName = requireName(sourceClass, officialNs, "class");
		String packagePrefixedIntermediaryName = prefixedIntermediaryName(officialClassName, intermediaryClassName, addPackagePrefix);
		MappingModel.ClassEntry classEntry = new MappingModel.ClassEntry(
				intermediaryClassName,
				intermediaryClassName,
				tinyClassTargetName(officialClassName, intermediaryClassName, packagePrefixedIntermediaryName, addPackagePrefix),
				sourceClass.getComment(),
				automaticClassTargetNames(officialClassName, intermediaryClassName, packagePrefixedIntermediaryName));

		for (MappingTreeView.FieldMappingView sourceField : sourceClass.getFields()) {
			classEntry.addField(readTinyField(sourceField, officialNs, intermediaryNs, intermediaryClassName));
		}

		for (MappingTreeView.MethodMappingView sourceMethod : sourceClass.getMethods()) {
			classEntry.addMethod(readTinyMethod(sourceMethod, officialNs, intermediaryNs, intermediaryClassName));
		}

		return classEntry;
	}

	private MappingModel.FieldEntry readTinyField(
			MappingTreeView.FieldMappingView sourceField,
			int officialNs,
			int intermediaryNs,
			String intermediaryClassName) throws ConversionException {
		String officialFieldName = requireName(sourceField, officialNs, "field in " + intermediaryClassName);
		boolean deobfuscated = ObfuscationHeuristic.fieldNameLooksDeobfuscated(officialFieldName);

		return new MappingModel.FieldEntry(
				requireName(sourceField, intermediaryNs, "field in " + intermediaryClassName),
				requireDesc(sourceField, intermediaryNs, "field " + sourceField.getSrcName() + " in " + intermediaryClassName),
				deobfuscated ? officialFieldName : null,
				sourceField.getComment(),
				deobfuscated);
	}

	private MappingModel.MethodEntry readTinyMethod(
			MappingTreeView.MethodMappingView sourceMethod,
			int officialNs,
			int intermediaryNs,
			String intermediaryClassName) throws ConversionException {
		String officialMethodName = requireName(sourceMethod, officialNs, "method in " + intermediaryClassName);
		MappingModel.MethodEntry methodEntry = new MappingModel.MethodEntry(
				requireName(sourceMethod, intermediaryNs, "method in " + intermediaryClassName),
				requireDesc(sourceMethod, intermediaryNs, "method " + sourceMethod.getSrcName() + " in " + intermediaryClassName),
				deobfuscatedMemberName(officialMethodName),
				sourceMethod.getComment());

		for (MappingTreeView.MethodArgMappingView sourceArg : sourceMethod.getArgs()) {
			addTinyArg(methodEntry, sourceArg, officialNs);
		}

		return methodEntry;
	}

	private void addTinyArg(
			MappingModel.MethodEntry methodEntry,
			MappingTreeView.MethodArgMappingView sourceArg,
			int officialNs) {
		if (sourceArg.getLvIndex() < 0) {
			return;
		}

		String officialArgName = sourceArg.getName(officialNs);
		if (officialArgName != null && ObfuscationHeuristic.memberNameLooksDeobfuscated(officialArgName)) {
			methodEntry.addArg(new MappingModel.ArgEntry(sourceArg.getLvIndex(), officialArgName, sourceArg.getComment()));
		}
	}

	private MappingModel readExistingEnigmaModel(MappingTreeView tree) throws ConversionException {
		int namedNs = namespaceId(tree, NAMED);
		int intermediaryNs = namespaceId(tree, INTERMEDIARY);
		MappingModel model = new MappingModel();

		for (MappingTreeView.ClassMappingView sourceClass : tree.getClasses()) {
			model.addClass(readExistingEnigmaClass(sourceClass, namedNs, intermediaryNs));
		}

		return model;
	}

	private MappingModel.ClassEntry readExistingEnigmaClass(
			MappingTreeView.ClassMappingView sourceClass,
			int namedNs,
			int intermediaryNs) throws ConversionException {
		String intermediaryClassName = requireName(sourceClass, intermediaryNs, "class");
		MappingModel.ClassEntry classEntry = new MappingModel.ClassEntry(
				intermediaryClassName,
				optionalName(sourceClass, namedNs),
				sourceClass.getComment());

		for (MappingTreeView.FieldMappingView sourceField : sourceClass.getFields()) {
			classEntry.addField(new MappingModel.FieldEntry(
					requireName(sourceField, intermediaryNs, "field in " + intermediaryClassName),
					requireDesc(sourceField, intermediaryNs, "field " + sourceField.getSrcName() + " in " + intermediaryClassName),
					optionalName(sourceField, namedNs),
					sourceField.getComment()));
		}

		for (MappingTreeView.MethodMappingView sourceMethod : sourceClass.getMethods()) {
			classEntry.addMethod(readExistingEnigmaMethod(sourceMethod, namedNs, intermediaryNs, intermediaryClassName));
		}

		return classEntry;
	}

	private MappingModel.MethodEntry readExistingEnigmaMethod(
			MappingTreeView.MethodMappingView sourceMethod,
			int namedNs,
			int intermediaryNs,
			String intermediaryClassName) throws ConversionException {
		MappingModel.MethodEntry methodEntry = new MappingModel.MethodEntry(
				requireName(sourceMethod, intermediaryNs, "method in " + intermediaryClassName),
				requireDesc(sourceMethod, intermediaryNs, "method " + sourceMethod.getSrcName() + " in " + intermediaryClassName),
				optionalName(sourceMethod, namedNs),
				sourceMethod.getComment());

		for (MappingTreeView.MethodArgMappingView sourceArg : sourceMethod.getArgs()) {
			if (sourceArg.getLvIndex() < 0) {
				continue;
			}

			String namedArg = optionalName(sourceArg, namedNs);
			if (namedArg != null) {
				methodEntry.addArg(new MappingModel.ArgEntry(sourceArg.getLvIndex(), namedArg, sourceArg.getComment()));
			}
		}

		return methodEntry;
	}

	private int namespaceId(MappingTreeView tree, String namespace) throws ConversionException {
		int id = tree.getNamespaceId(namespace);
		if (id == NULL_NAMESPACE_ID) {
			throw new ConversionException("Mapping tree is missing namespace: " + namespace);
		}

		return id;
	}

	private String requireName(
			MappingTreeView.ElementMappingView element,
			int namespace,
			String description) throws ConversionException {
		String name = element.getName(namespace);
		if (name == null || name.isEmpty()) {
			throw new ConversionException("Missing " + description + " name in required namespace");
		}

		return name;
	}

	private String optionalName(MappingTreeView.ElementMappingView element, int namespace) {
		String name = element.getName(namespace);
		return name == null || name.isEmpty() ? null : name;
	}

	private String requireDesc(
			MappingTreeView.MemberMappingView element,
			int namespace,
			String description) throws ConversionException {
		String desc = element.getDesc(namespace);
		if (desc == null || desc.isEmpty()) {
			throw new ConversionException("Missing descriptor for " + description);
		}

		return desc;
	}

	private String deobfuscatedClassName(String officialName) {
		return ObfuscationHeuristic.classNameLooksDeobfuscated(officialName) ? officialName : null;
	}

	private String deobfuscatedMemberName(String officialName) {
		return ObfuscationHeuristic.memberNameLooksDeobfuscated(officialName) ? officialName : null;
	}

	private String tinyClassTargetName(
			String officialClassName,
			String intermediaryClassName,
			String packagePrefixedIntermediaryName,
			boolean addPackagePrefix) {
		if (addPackagePrefix && !packagePrefixedIntermediaryName.equals(intermediaryClassName)) {
			return packagePrefixedIntermediaryName;
		}

		return deobfuscatedClassName(officialClassName);
	}

	private Set<String> automaticClassTargetNames(
			String officialClassName,
			String intermediaryClassName,
			String packagePrefixedIntermediaryName) {
		Set<String> names = new LinkedHashSet<>();
		String deobfuscatedClassName = deobfuscatedClassName(officialClassName);
		if (deobfuscatedClassName != null) {
			names.add(deobfuscatedClassName);
		}

		if (!packagePrefixedIntermediaryName.equals(intermediaryClassName)) {
			names.add(packagePrefixedIntermediaryName);
		}

		return names;
	}

	private String prefixedIntermediaryName(
			String officialClassName,
			String intermediaryClassName,
			boolean addPackagePrefix) {
		if (!addPackagePrefix) {
			return intermediaryClassName;
		}

		int packageIndex = officialClassName.lastIndexOf('/');
		if (packageIndex < 0) {
			return intermediaryClassName;
		}

		return officialClassName.substring(0, packageIndex + 1) + intermediaryClassName;
	}
}
