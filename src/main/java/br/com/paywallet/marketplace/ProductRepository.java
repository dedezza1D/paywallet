package br.com.paywallet.marketplace;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

interface ProductRepository extends JpaRepository<Product, String> {

    List<Product> findByActiveTrueOrderByCategoryAscBrandAsc();

    List<Product> findByActiveTrueAndCategoryOrderByBrand(Product.Category category);
}
