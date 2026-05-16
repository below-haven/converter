package eu.ponei.converter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class MappingModelMerger {
	private static final String CONSTRUCTOR_NAME = "<init>";

	void mergeInto(MappingModel current, MappingModel existing) throws ConversionException {
		for (MappingModel.ClassEntry currentClass : current.classes()) {
			MappingModel.ClassEntry existingClass = existing.classByIntermediary(currentClass.intermediaryName());
			if (existingClass == null) {
				continue;
			}

			preserveClass(currentClass, existingClass);
			mergeFields(currentClass, existingClass);
			mergeMethods(currentClass, existingClass);
		}
	}

	private void preserveClass(MappingModel.ClassEntry currentClass, MappingModel.ClassEntry existingClass) {
		if (isMeaningfulClassName(existingClass.namedName(), existingClass.intermediaryName())) {
			currentClass.setNamedName(existingClass.namedName());
		}

		currentClass.setComment(existingClass.comment());
	}

	private void mergeFields(
			MappingModel.ClassEntry currentClass,
			MappingModel.ClassEntry existingClass) throws ConversionException {
		mergeMembers(
				currentClass.intermediaryName(),
				"field",
				currentClass.fields(),
				existingClass.fields(),
				existingClass::fieldByExactKey);
	}

	private void mergeMethods(
			MappingModel.ClassEntry currentClass,
			MappingModel.ClassEntry existingClass) throws ConversionException {
		Set<MappingModel.MethodEntry> usedExisting = mergeMembers(
				currentClass.intermediaryName(),
				"method",
				currentClass.methods(),
				existingClass.methods(),
				existingClass::methodByExactKey);
		preserveExistingConstructors(currentClass, existingClass, usedExisting);
	}

	private <T extends MappingModel.MemberEntry> Set<T> mergeMembers(
			String owner,
			String kind,
			List<T> currentMembers,
			List<T> existingMembers,
			ExactMemberLookup<T> exactLookup) throws ConversionException {
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
			Set<MappingModel.MethodEntry> usedExisting) throws ConversionException {
		for (MappingModel.MethodEntry existingMethod : existingClass.methods()) {
			if (usedExisting.contains(existingMethod)
					|| !existingMethod.intermediaryName().equals(CONSTRUCTOR_NAME)) {
				continue;
			}

			currentClass.addMethod(copyMethod(existingMethod));
		}
	}

	private MappingModel.MethodEntry copyMethod(MappingModel.MethodEntry method) {
		MappingModel.MethodEntry copy = new MappingModel.MethodEntry(
				method.intermediaryName(),
				method.intermediaryDesc(),
				method.namedName(),
				method.comment());

		for (MappingModel.ArgEntry arg : method.args()) {
			copy.addArg(new MappingModel.ArgEntry(arg.lvIndex(), arg.namedName(), arg.comment()));
		}

		return copy;
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
