package cm.hitechdz.auth.config;

import cm.hitechdz.auth.domain.catalog.Brand;
import cm.hitechdz.auth.domain.catalog.Category;
import cm.hitechdz.auth.domain.catalog.Product;
import cm.hitechdz.auth.domain.catalog.ProductImage;
import cm.hitechdz.auth.domain.catalog.ProductSpec;
import cm.hitechdz.auth.domain.catalog.ProductStatus;
import cm.hitechdz.auth.domain.catalog.ProductVariant;
import cm.hitechdz.auth.domain.catalog.Review;
import cm.hitechdz.auth.repository.catalog.BrandRepository;
import cm.hitechdz.auth.repository.catalog.CategoryRepository;
import cm.hitechdz.auth.repository.catalog.ProductRepository;
import cm.hitechdz.auth.repository.catalog.ReviewRepository;
import cm.hitechdz.auth.service.catalog.CatalogSeedSupport;
import cm.hitechdz.auth.util.SlugUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Données de démonstration du catalogue : 6 catégories, 10 marques, 12 produits publiés,
 * des variantes et des avis. Exécuté seulement avec le profil « dev » ou SEED_CATALOG=true,
 * et uniquement si le catalogue est vide.
 */
@Component
public class CatalogSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CatalogSeeder.class);

    private final Environment environment;
    private final boolean seedEnabled;
    private final CategoryRepository categoryRepository;
    private final BrandRepository brandRepository;
    private final ProductRepository productRepository;
    private final ReviewRepository reviewRepository;
    private final CatalogSeedSupport support;

    public CatalogSeeder(Environment environment, @Value("${app.seed.catalog:false}") boolean seedEnabled,
                         CategoryRepository categoryRepository, BrandRepository brandRepository,
                         ProductRepository productRepository, ReviewRepository reviewRepository,
                         CatalogSeedSupport support) {
        this.environment = environment;
        this.seedEnabled = seedEnabled;
        this.categoryRepository = categoryRepository;
        this.brandRepository = brandRepository;
        this.productRepository = productRepository;
        this.reviewRepository = reviewRepository;
        this.support = support;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        boolean enabled = seedEnabled || environment.acceptsProfiles(Profiles.of("dev"));
        if (!enabled || categoryRepository.count() > 0 || productRepository.count() > 0) {
            return;
        }

        Map<String, Category> cats = new HashMap<>();
        cats.put("ordinateurs", category("Ordinateurs", "laptop", 0, List.of("Portables", "Ordinateurs de bureau", "Gaming")));
        cats.put("smartphones", category("Smartphones", "smartphone", 1, List.of("Android", "iPhone", "Tablettes")));
        cats.put("accessoires", category("Accessoires", "headphones", 2, List.of("Écouteurs", "Souris & claviers", "Enceintes")));
        cats.put("reseau", category("Réseau", "router", 3, List.of("Routeurs", "Wi-Fi", "Switchs")));
        cats.put("electronique", category("Électronique", "tv", 4, List.of("Téléviseurs", "Audio", "Consoles")));
        cats.put("stockage", category("Stockage", "harddrive", 5, List.of("Disques durs", "SSD", "Clés USB")));

        Map<String, Brand> brands = new HashMap<>();
        for (String name : List.of("HP", "Samsung", "Apple", "Dell", "Lenovo", "Sony", "TP-Link", "Tecno", "Logitech", "JBL")) {
            Brand b = new Brand();
            b.setName(name);
            b.setSlug(SlugUtil.slugify(name));
            brands.put(name, brandRepository.save(b));
        }

        Product hp = product("HP Pavilion 15 Core i5 16 Go", brands.get("HP"), cats.get("ordinateurs"),
                425_000, 465_000, 14, 37, false, "Ordinateur portable polyvalent pour le travail et les études.",
                Map.of("Processeur", "Intel Core i5-1335U", "RAM", "16 Go", "Stockage", "SSD 512 Go", "Écran", "15,6\" Full HD"));
        Product dell = product("Dell Latitude 5440", brands.get("Dell"), cats.get("ordinateurs"),
                610_000, null, 6, 12, false, "Portable professionnel robuste, idéal pour l'entreprise.",
                Map.of("Processeur", "Intel Core i7-1355U", "RAM", "16 Go", "Stockage", "SSD 512 Go"));
        Product lenovo = product("Lenovo IdeaPad Slim 3", brands.get("Lenovo"), cats.get("ordinateurs"),
                315_000, 350_000, 3, 21, true, "Léger et autonome, parfait pour les étudiants.",
                Map.of("Processeur", "AMD Ryzen 5 7520U", "RAM", "8 Go", "Stockage", "SSD 256 Go"));
        Product galaxy = product("Samsung Galaxy A55 5G", brands.get("Samsung"), cats.get("smartphones"),
                285_000, 310_000, 0, 58, true, "Écran Super AMOLED 120 Hz et triple capteur photo.",
                Map.of("Écran", "6,6\" Super AMOLED", "Batterie", "5000 mAh", "Réseau", "5G"));
        Product iphone = product("Apple iPhone 15", brands.get("Apple"), cats.get("smartphones"),
                650_000, null, 0, 44, true, "Dynamic Island, appareil photo 48 Mpx et USB-C.",
                Map.of("Écran", "6,1\" Super Retina XDR", "Puce", "A16 Bionic", "Connecteur", "USB-C"));
        Product tecno = product("Tecno Camon 30", brands.get("Tecno"), cats.get("smartphones"),
                145_000, 160_000, 25, 96, false, "Grand écran et excellente autonomie à petit prix.",
                Map.of("Écran", "6,78\" AMOLED", "Batterie", "5000 mAh", "Stockage", "256 Go"));
        Product jbl = product("JBL Tune 520BT Écouteurs sans fil", brands.get("JBL"), cats.get("accessoires"),
                32_000, 38_000, 40, 132, false, "Casque Bluetooth léger, jusqu'à 57 h d'autonomie.",
                Map.of("Connexion", "Bluetooth 5.3", "Autonomie", "57 h"));
        Product mouse = product("Logitech MX Master 3S", brands.get("Logitech"), cats.get("accessoires"),
                68_000, null, 4, 29, false, "Souris ergonomique silencieuse, défilement ultra-rapide.",
                Map.of("Connexion", "Bluetooth / Logi Bolt", "Capteur", "8000 DPI"));
        Product router = product("TP-Link Archer AX55 Wi-Fi 6", brands.get("TP-Link"), cats.get("reseau"),
                55_000, 62_000, 18, 41, false, "Routeur Wi-Fi 6 double bande jusqu'à 3 Gbit/s.",
                Map.of("Norme", "Wi-Fi 6 (802.11ax)", "Débit", "AX3000", "Ports", "4 x Gigabit"));
        Product tv = product("Samsung Smart TV Crystal UHD 55\"", brands.get("Samsung"), cats.get("electronique"),
                395_000, 450_000, 7, 18, true, "Téléviseur 4K avec Tizen et HDR.",
                Map.of("Taille", "55 pouces", "Résolution", "4K UHD", "Système", "Tizen"));
        Product ps5 = product("Sony PlayStation 5 Slim", brands.get("Sony"), cats.get("electronique"),
                420_000, null, 2, 27, true, "La console nouvelle génération, version Slim avec lecteur.",
                Map.of("Stockage", "1 To SSD", "Résolution", "Jusqu'à 4K 120 Hz"));
        Product ssd = product("Samsung SSD 990 PRO 1 To", brands.get("Samsung"), cats.get("stockage"),
                95_000, 110_000, 22, 35, false, "SSD NVMe PCIe 4.0 ultra-rapide jusqu'à 7450 Mo/s.",
                Map.of("Interface", "PCIe 4.0 NVMe", "Lecture", "7450 Mo/s", "Format", "M.2 2280"));

        // Variantes : couleur × capacité pour le Galaxy, couleur × capacité pour l'iPhone
        variant(galaxy, "A55-NOIR-128", "Noir", "#1F1F1F", "128 Go", null, 0, 8, 0);
        variant(galaxy, "A55-NOIR-256", "Noir", "#1F1F1F", "256 Go", null, 35_000, 5, 1);
        variant(galaxy, "A55-BLEU-128", "Bleu", "#3B6FD8", "128 Go", null, 0, 6, 2);
        variant(galaxy, "A55-BLEU-256", "Bleu", "#3B6FD8", "256 Go", null, 35_000, 0, 3);
        variant(iphone, "IP15-NOIR-128", "Noir", "#2B2B2B", "128 Go", null, 0, 4, 0);
        variant(iphone, "IP15-ROSE-128", "Rose", "#F4C7D0", "128 Go", null, 0, 3, 1);
        variant(iphone, "IP15-NOIR-256", "Noir", "#2B2B2B", "256 Go", null, 90_000, 2, 2);
        galaxy.recomputeStockFromVariants();
        iphone.recomputeStockFromVariants();

        for (Product p : List.of(hp, dell, lenovo, galaxy, iphone, tecno, jbl, mouse, router, tv, ps5, ssd)) {
            support.refreshSearchText(p);
            productRepository.save(p);
        }
        productRepository.flush();

        review(hp, "Arnaud", "Douala", 5, "Très bon rapport qualité-prix", "Rapide, silencieux et l'écran est de très bonne qualité. Livré en 48 h à Douala.", 12, 20);
        review(hp, "Clarisse", "Yaoundé", 4, "Bon ordinateur", "Performant pour la bureautique, la batterie tient environ 6 heures.", 3, 9);
        review(galaxy, "Brice", "Bafoussam", 5, "Superbe écran", "L'écran est magnifique et la batterie tient facilement la journée.", 8, 15);
        review(galaxy, "Mireille", "Douala", 4, "Très satisfaite", "Belles photos de jour, un peu moins la nuit. Je recommande.", 2, 6);
        review(jbl, "Junior", "Garoua", 5, "Son puissant", "Basses profondes et autonomie impressionnante, parfait pour les trajets.", 5, 3);
        reviewRepository.flush();
        for (Product p : List.of(hp, galaxy, jbl)) {
            support.recomputeRating(p);
        }

        log.info("Catalogue de démonstration créé : 6 catégories, 10 marques, 12 produits");
    }

    // ------------------------------------------------------------------ helpers
    private Category category(String name, String icon, int position, List<String> subs) {
        Category c = new Category();
        c.setName(name);
        c.setSlug(SlugUtil.slugify(name));
        c.setIcon(icon);
        c.setPosition(position);
        c.setSub(subs);
        return categoryRepository.save(c);
    }

    private static Product product(String name, Brand brand, Category category, long price, Long oldPrice,
                                   int stock, int sold, boolean isNew, String description, Map<String, String> specs) {
        Product p = new Product();
        p.setName(name);
        p.setSlug(SlugUtil.slugify(name));
        p.setBrand(brand);
        p.setCategory(category);
        p.setPrice(price);
        p.setOldPrice(oldPrice);
        p.setStock(stock);
        p.setSold(sold);
        p.setNewProduct(isNew);
        p.setStatus(ProductStatus.PUBLISHED);
        p.setDescription(description);
        specs.forEach((k, v) -> p.getSpecs().add(new ProductSpec(k, v)));
        String text = URLEncoder.encode(name, StandardCharsets.UTF_8);
        p.addImage(new ProductImage("https://placehold.co/800x800/png?text=" + text, null, 800, 800));
        return p;
    }

    private static void variant(Product p, String sku, String color, String hex, String capacity, String ram,
                                long delta, int stock, int position) {
        ProductVariant v = new ProductVariant();
        v.setSku(sku);
        v.setColor(color);
        v.setColorHex(hex);
        v.setCapacity(capacity);
        v.setRam(ram);
        v.setPriceDelta(delta);
        v.setStock(stock);
        v.setPosition(position);
        p.addVariant(v);
    }

    private void review(Product p, String author, String city, int rating, String title, String body,
                        int helpful, int daysAgo) {
        Review r = new Review();
        r.setProduct(p);
        r.setAuthorName(author);
        r.setAuthorCity(city);
        r.setRating(rating);
        r.setTitle(title);
        r.setBody(body);
        r.setVerified(true);
        r.setCreatedAt(Instant.now().minus(Duration.ofDays(daysAgo)));
        r.setHelpfulCount(helpful);
        reviewRepository.save(r);
    }
}
