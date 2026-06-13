package eu.ponei.converter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.fabricmc.mappingio.MappingUtil;

final class MappingModelMerger {
	private static final String CONSTRUCTOR_NAME = "<init>";

	void mergeInto(MappingModel current, MappingModel existing) throws ConversionException {
		Map<String, String> descriptorNormalizationMap = descriptorNormalizationMap(current, existing);
		Map<String, String> existingToCurrentClassNames = existingToCurrentClassNames(current, existing);

		for (MappingModel.ClassEntry currentClass : current.classes()) {
			MappingModel.ClassEntry existingClass = matchingExistingClass(currentClass, existing);
			if (existingClass == null) {
				continue;
			}

			preserveClass(currentClass, existingClass);
			mergeFields(currentClass, existingClass, descriptorNormalizationMap);
			mergeMethods(currentClass, existingClass, descriptorNormalizationMap, existingToCurrentClassNames);
		}
	}

	private MappingModel.ClassEntry matchingExistingClass(
			MappingModel.ClassEntry currentClass,
			MappingModel existing) {
		MappingModel.ClassEntry exact = existing.classByIntermediary(currentClass.intermediaryName());
		if (exact != null) {
			return exact;
		}

		return existing.classByUnqualifiedIntermediary(currentClass.unqualifiedIntermediaryName());
	}

	private void preserveClass(MappingModel.ClassEntry currentClass, MappingModel.ClassEntry existingClass) {
		if (isMeaningfulClassName(existingClass.namedName(), existingClass.intermediaryName())
				&& !currentClass.isAutomaticNamedName(existingClass.namedName())) {
			currentClass.setNamedName(existingClass.namedName());
		}

		currentClass.setComment(existingClass.comment());
	}

	private void mergeFields(
			MappingModel.ClassEntry currentClass,
			MappingModel.ClassEntry existingClass,
			Map<String, String> descriptorNormalizationMap) throws ConversionException {
		mergeMembers(
				currentClass.intermediaryName(),
				"field",
				currentClass.fields(),
				existingClass.fields(),
				existingClass::fieldByExactKey,
				descriptorNormalizationMap);
	}

	private void mergeMethods(
			MappingModel.ClassEntry currentClass,
			MappingModel.ClassEntry existingClass,
			Map<String, String> descriptorNormalizationMap,
			Map<String, String> existingToCurrentClassNames) throws ConversionException {
		Set<MappingModel.MethodEntry> usedExisting = mergeMembers(
				currentClass.intermediaryName(),
				"method",
				currentClass.methods(),
				existingClass.methods(),
				existingClass::methodByExactKey,
				descriptorNormalizationMap);
		preserveExistingConstructors(currentClass, existingClass, usedExisting, existingToCurrentClassNames);
	}

	private <T extends MappingModel.MemberEntry> Set<T> mergeMembers(
			String owner,
			String kind,
			List<T> currentMembers,
			List<T> existingMembers,
			ExactMemberLookup<T> exactLookup,
			Map<String, String> descriptorNormalizationMap) throws ConversionException {
		Set<T> exactMatched = new LinkedHashSet<>();
		Set<T> migrated = new LinkedHashSet<>();
		Set<T> usedExisting = new LinkedHashSet<>();

		for (T current : currentMembers) {
			T existing = exactLookup.find(current.key());
			if (existing == null) {
				continue;
			}

			preserveMember(current, existing);
			exactMatched.add(current);
			usedExisting.add(existing);
		}

		for (T current : currentMembers) {
			if (exactMatched.contains(current) || migrated.contains(current)) {
				continue;
			}

			String normalizedDesc = normalizeDesc(current.intermediaryDesc(), descriptorNormalizationMap);
			List<T> currentCandidates = unmatchedByNormalizedKey(
					currentMembers,
					exactMatched,
					migrated,
					current.intermediaryName(),
					normalizedDesc,
					descriptorNormalizationMap);
			List<T> existingCandidates = unusedByNormalizedKey(
					existingMembers,
					usedExisting,
					current.intermediaryName(),
					normalizedDesc,
					descriptorNormalizationMap);

			if (existingCandidates.isEmpty()) {
				continue;
			}

			if (currentCandidates.size() != 1 || existingCandidates.size() != 1) {
				throw new ConversionException("Ambiguous " + kind + " descriptor change for "
						+ owner + "." + current.intermediaryName());
			}

			T currentCandidate = currentCandidates.getFirst();
			T existingCandidate = existingCandidates.getFirst();
			preserveMember(currentCandidate, existingCandidate);
			migrated.add(currentCandidate);
			usedExisting.add(existingCandidate);
		}

		for (T current : currentMembers) {
			if (exactMatched.contains(current) || migrated.contains(current)) {
				continue;
			}

			List<T> currentCandidates = unmatchedByName(currentMembers, exactMatched, migrated, current.intermediaryName());
			List<T> existingCandidates = unusedByName(existingMembers, usedExisting, current.intermediaryName());

			if (existingCandidates.isEmpty()) {
				continue;
			}

			if (currentCandidates.size() != 1 || existingCandidates.size() != 1) {
				throw new ConversionException("Ambiguous " + kind + " descriptor change for "
						+ owner + "." + current.intermediaryName());
			}

			T currentCandidate = currentCandidates.getFirst();
			T existingCandidate = existingCandidates.getFirst();
			preserveMember(currentCandidate, existingCandidate);
			migrated.add(currentCandidate);
			usedExisting.add(existingCandidate);
		}

		return usedExisting;
	}

	private <T extends MappingModel.MemberEntry> List<T> unmatchedByNormalizedKey(
			List<T> members,
			Set<T> exactMatched,
			Set<T> migrated,
			String intermediaryName,
			String normalizedDesc,
			Map<String, String> descriptorNormalizationMap) {
		List<T> candidates = new ArrayList<>();

		for (T member : members) {
			if (!exactMatched.contains(member)
					&& !migrated.contains(member)
					&& member.intermediaryName().equals(intermediaryName)
					&& normalizeDesc(member.intermediaryDesc(), descriptorNormalizationMap).equals(normalizedDesc)) {
				candidates.add(member);
			}
		}

		return candidates;
	}

	private <T extends MappingModel.MemberEntry> List<T> unusedByNormalizedKey(
			List<T> members,
			Set<T> used,
			String intermediaryName,
			String normalizedDesc,
			Map<String, String> descriptorNormalizationMap) {
		List<T> candidates = new ArrayList<>();

		for (T member : members) {
			if (!used.contains(member)
					&& member.intermediaryName().equals(intermediaryName)
					&& normalizeDesc(member.intermediaryDesc(), descriptorNormalizationMap).equals(normalizedDesc)) {
				candidates.add(member);
			}
		}

		return candidates;
	}

	private <T extends MappingModel.MemberEntry> List<T> unmatchedByName(
			List<T> members,
			Set<T> exactMatched,
			Set<T> migrated,
			String intermediaryName) {
		List<T> candidates = new ArrayList<>();

		for (T member : members) {
			if (!exactMatched.contains(member)
					&& !migrated.contains(member)
					&& member.intermediaryName().equals(intermediaryName)) {
				candidates.add(member);
			}
		}

		return candidates;
	}

	private <T extends MappingModel.MemberEntry> List<T> unusedByName(
			List<T> members,
			Set<T> used,
			String intermediaryName) {
		List<T> candidates = new ArrayList<>();

		for (T member : members) {
			if (!used.contains(member) && member.intermediaryName().equals(intermediaryName)) {
				candidates.add(member);
			}
		}

		return candidates;
	}

	private void preserveMember(MappingModel.MemberEntry current, MappingModel.MemberEntry existing) {
		if (existing.namedName() != null) {
			current.setNamedName(existing.namedName());
		}

		current.setComment(existing.comment());

		if (current instanceof MappingModel.FieldEntry currentField && existing instanceof MappingModel.FieldEntry) {
			currentField.setWriteWhenUnnamed(true);
		}

		if (current instanceof MappingModel.MethodEntry currentMethod && existing instanceof MappingModel.MethodEntry existingMethod) {
			preserveMethodArgs(currentMethod, existingMethod);
		}
	}

	private void preserveMethodArgs(MappingModel.MethodEntry currentMethod, MappingModel.MethodEntry existingMethod) {
		for (MappingModel.ArgEntry existingArg : existingMethod.args()) {
			MappingModel.ArgEntry currentArg = currentMethod.argByLvIndex(existingArg.lvIndex());
			if (currentArg == null) {
				currentMethod.addArg(new MappingModel.ArgEntry(
						existingArg.lvIndex(),
						existingArg.namedName(),
						existingArg.comment()));
				continue;
			}

			if (existingArg.namedName() != null) {
				currentArg.setNamedName(existingArg.namedName());
			}

			currentArg.setComment(existingArg.comment());
		}
	}

	private void preserveExistingConstructors(
			MappingModel.ClassEntry currentClass,
			MappingModel.ClassEntry existingClass,
			Set<MappingModel.MethodEntry> usedExisting,
			Map<String, String> existingToCurrentClassNames) throws ConversionException {
		for (MappingModel.MethodEntry existingMethod : existingClass.methods()) {
			if (usedExisting.contains(existingMethod)
					|| !existingMethod.intermediaryName().equals(CONSTRUCTOR_NAME)) {
				continue;
			}

			currentClass.addMethod(copyMethod(existingMethod, existingToCurrentClassNames));
		}
	}

	private MappingModel.MethodEntry copyMethod(
			MappingModel.MethodEntry method,
			Map<String, String> existingToCurrentClassNames) {
		MappingModel.MethodEntry copy = new MappingModel.MethodEntry(
				method.intermediaryName(),
				MappingUtil.mapDesc(method.intermediaryDesc(), existingToCurrentClassNames),
				method.namedName(),
				method.comment());

		for (MappingModel.ArgEntry arg : method.args()) {
			copy.addArg(new MappingModel.ArgEntry(arg.lvIndex(), arg.namedName(), arg.comment()));
		}

		return copy;
	}

	private Map<String, String> descriptorNormalizationMap(MappingModel current, MappingModel existing) {
		Map<String, String> map = new LinkedHashMap<>();
		addDescriptorNormalizationNames(map, current);
		addDescriptorNormalizationNames(map, existing);
		return map;
	}

	private void addDescriptorNormalizationNames(Map<String, String> map, MappingModel model) {
		for (MappingModel.ClassEntry classEntry : model.classes()) {
			map.put(classEntry.intermediaryName(), classEntry.unqualifiedIntermediaryName());
		}
	}

	private Map<String, String> existingToCurrentClassNames(MappingModel current, MappingModel existing) {
		Map<String, String> map = new LinkedHashMap<>();

		for (MappingModel.ClassEntry existingClass : existing.classes()) {
			MappingModel.ClassEntry currentClass = current.classByIntermediary(existingClass.intermediaryName());
			if (currentClass == null) {
				currentClass = current.classByUnqualifiedIntermediary(existingClass.unqualifiedIntermediaryName());
			}

			if (currentClass != null) {
				map.put(existingClass.intermediaryName(), currentClass.intermediaryName());
			}
		}

		return map;
	}

	private String normalizeDesc(String desc, Map<String, String> descriptorNormalizationMap) {
		return MappingUtil.mapDesc(desc, descriptorNormalizationMap);
	}

	private boolean isMeaningfulClassName(String namedName, String intermediaryName) {
		return namedName != null && !innermostClassName(namedName).equals(innermostClassName(intermediaryName));
	}

	private String innermostClassName(String name) {
		int innerClassIndex = name.lastIndexOf('$');
		int packageIndex = name.lastIndexOf('/');
		int index = Math.max(innerClassIndex, packageIndex);
		return index >= 0 ? name.substring(index + 1) : name;
	}

	private interface ExactMemberLookup<T extends MappingModel.MemberEntry> {
		T find(MappingModel.MemberKey key);
	}
}
