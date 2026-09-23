package org.example.grab.domain.drop.repository;

import org.example.grab.domain.drop.entity.Drop;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DropRepository extends JpaRepository<Drop, Long> {

    @EntityGraph(attributePaths = "options")
    Optional<Drop> findWithOptionsById(Long id);
}
