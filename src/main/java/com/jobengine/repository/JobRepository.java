package com.jobengine.repository;

import com.jobengine.model.Job;
import com.jobengine.model.JobStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface JobRepository extends JpaRepository<Job, UUID> {

    List<Job> findByStatus(JobStatus status);

    List<Job> findByTaskType(String taskType);
}
