package eu.ponei.converter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class MappingModelMerger {
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
		if (existingClass.namedName() != null) {
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
		mergeMembers(
				currentClass.intermediaryName(),
				"method",
				currentClass.methods(),
				existingClass.methods(),
				existingClass::methodByExactKey);
	}

	private <T extends MappingModel.MemberEntry> void mergeMembers(
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

		if (current instanceof MappingModel.MethodEntry currentMethod && existing instanceof MappingModel.MethodEntry existingMethod) {
			preserveMethodArgs(currentMethod, existingMethod);
		}
	}

	private void preserveMethodArgs(MappingModel.MethodEntry currentMethod, MappingModel.MethodEntry existingMethod) {
		for (MappingModel.ArgEntry currentArg : currentMethod.args()) {
			MappingModel.ArgEntry existingArg = existingMethod.argByLvIndex(currentArg.lvIndex());
			if (existingArg == null) {
				continue;
			}

			if (existingArg.namedName() != null) {
				currentArg.setNamedName(existingArg.namedName());
			}

			currentArg.setComment(existingArg.comment());
		}
	}

	private interface ExactMemberLookup<T extends MappingModel.MemberEntry> {
		T find(MappingModel.MemberKey key);
	}
}
