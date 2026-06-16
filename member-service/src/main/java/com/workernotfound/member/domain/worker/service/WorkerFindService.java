package com.workernotfound.member.domain.worker.service;

import com.workernotfound.member.domain.worker.entity.WorkerProfile;
import com.workernotfound.member.domain.worker.repository.WorkerProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkerFindService {

	private final WorkerProfileRepository workerProfileRepository;

	public WorkerProfile findWorkerProfile(Long memberId) {
		return workerProfileRepository.findByMember_Id(memberId)
			.orElseThrow(() -> new IllegalArgumentException("근로자 프로필을 찾을 수 없습니다."));
	}
}
