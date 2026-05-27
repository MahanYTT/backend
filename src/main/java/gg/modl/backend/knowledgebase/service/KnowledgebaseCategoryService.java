package gg.modl.backend.knowledgebase.service;

import com.github.slugify.Slugify;
import gg.modl.backend.database.mongo.repository.KnowledgebaseArticleMongoRepository;
import gg.modl.backend.database.mongo.repository.KnowledgebaseCategoryMongoRepository;
import gg.modl.backend.knowledgebase.data.KnowledgebaseCategory;
import gg.modl.backend.knowledgebase.dto.request.CreateCategoryRequest;
import gg.modl.backend.knowledgebase.dto.request.UpdateCategoryRequest;
import gg.modl.backend.server.data.Server;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgebaseCategoryService {
    private final KnowledgebaseCategoryMongoRepository categoryRepository;
    private final KnowledgebaseArticleMongoRepository articleRepository;
    private final Slugify slugify = Slugify.builder().build();

    public @NotNull List<KnowledgebaseCategory> getAllCategories(@NotNull Server server) {
        return categoryRepository.findAllOrdered(server);
    }

    public @NotNull List<KnowledgebaseCategory> getVisibleCategories(@NotNull Server server) {
        return categoryRepository.findVisibleOrdered(server);
    }

    public @NotNull Optional<KnowledgebaseCategory> getCategoryById(@NotNull Server server, @NotNull String id) {
        return categoryRepository.findByCategoryId(server, id);
    }

    public @NotNull KnowledgebaseCategory createCategory(@NotNull Server server, @NotNull CreateCategoryRequest request) {
        KnowledgebaseCategory category = KnowledgebaseCategory.builder()
            .name(request.name())
            .slug(slugify.slugify(request.name()))
            .description(request.description())
            .ordinal(categoryRepository.findMaxOrdinal(server) + 1)
            .isVisible(true)
            .createdAt(new Date())
            .updatedAt(new Date())
            .build();

        return categoryRepository.saveEntity(server, category);
    }

    public @NotNull Optional<KnowledgebaseCategory> updateCategory(@NotNull Server server, @NotNull String id, @NotNull UpdateCategoryRequest request) {
        return categoryRepository.updateCategory(
            server,
            id,
            request.name(),
            request.name() != null ? slugify.slugify(request.name()) : null,
            request.description(),
            request.isVisible(),
            new Date()
        );
    }

    public boolean deleteCategory(Server server, String id) {
        articleRepository.deleteByCategoryId(server, id);
        return categoryRepository.deleteByCategoryId(server, id);
    }

    public void reorderCategories(Server server, List<String> ids) {
        categoryRepository.reorderCategories(server, ids);
    }
}
