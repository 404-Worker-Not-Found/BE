package com.workernotfound.matching.domain.matching.service;

import com.workernotfound.matching.domain.matching.entity.Matching;
import com.workernotfound.matching.domain.matching.exception.MatchingErrorCode;
import com.workernotfound.matching.domain.matching.repository.MatchingRepository;
import com.workernotfound.matching.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MatchingFindService {

	private final MatchingRepository matchingRepository;

	public Matching findOwnedMatching(Long matchingId, Long workerMemberId) {
		Matching matching = matchingRepository.findById(matchingId)
			.orElseThrow(() -> new BusinessException(MatchingErrorCode.MATCHING_NOT_FOUND));
		if (!matching.getWorkerMemberId().equals(workerMemberId)) {
			throw new BusinessException(MatchingErrorCode.MATCHING_FORBIDDEN);
		}
		return matching;
	}

	public Page<Matching> findWorkerMatchings(Long workerMemberId, int page, int size) {
		Sort sort = Sort.by(Sort.Order.desc("selectedAt"), Sort.Order.desc("id"));
		return matchingRepository.findByWorkerMemberId(workerMemberId, PageRequest.of(page, size, sort));
	}
}
