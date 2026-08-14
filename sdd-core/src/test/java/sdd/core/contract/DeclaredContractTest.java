package sdd.core.contract;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeclaredContractTest {

    @Test
    void aJavaApiDeclarationCanonicalizesToTheActualizersOwnShape() {
        DeclaredContract declared = DeclaredContract.parse("java-api",
                "com.trading.pricing.core.JdbcTierResolver#resolveTier(String): ClientTier");
        assertThat(declared.members())
                .containsExactly("com.trading.pricing.core.JdbcTierResolver#resolveTier(String):ClientTier");
        assertThat(declared.problems()).isEmpty();
        assertThat(declared.missingFrom("""
                # actualized (java-api)
                com.trading.pricing.core.JdbcTierResolver
                  loadAll(): void
                  resolveTier(String): ClientTier
                """)).isEmpty();
    }

    @Test
    void aWrongReturnTypeIsMissingEvenThoughTheMethodNameMatches() {
        // The real trading-product-a failure: shipped Tier where the contract said Optional<Tier>.
        DeclaredContract declared = DeclaredContract.parse("java-api",
                "com.trading.pricing.core.TierResolver#tierFor(String): Optional<Tier>");
        assertThat(declared.missingFrom("""
                com.trading.pricing.core.TierResolver
                  tierFor(String): Tier
                """))
                .containsExactly("com.trading.pricing.core.TierResolver#tierFor(String):Optional<Tier>");
    }

    @Test
    void typesCompareBySimpleNameIncludingInsideGenerics() {
        DeclaredContract declared = DeclaredContract.parse("java-api",
                "com.trading.pricing.core.TierResolver#tierFor(java.lang.String): java.util.Optional<com.trading.model.Tier>");
        assertThat(declared.missingFrom("""
                com.trading.pricing.core.TierResolver
                  tierFor(String): Optional<Tier>
                """)).isEmpty();
    }

    @Test
    void extraActualMembersAreNotDivergence() {
        DeclaredContract declared = DeclaredContract.parse("java-api",
                "com.trading.tier.ClientTier#getTier(): Tier");
        assertThat(declared.missingFrom("""
                com.trading.tier.ClientTier
                  getClientId(): String
                  getTier(): Tier
                  equals(Object): boolean
                """)).isEmpty();
    }

    @Test
    void aDeclaredTypeThatDoesNotExistAtAllIsMissing() {
        DeclaredContract declared = DeclaredContract.parse("java-api",
                "com.trading.absent.Nope#gone(): void");
        assertThat(declared.missingFrom("com.trading.tier.ClientTier\n  getTier(): Tier\n"))
                .containsExactly("com.trading.absent.Nope#gone():void");
    }

    @Test
    void restComparesMethodAndPathAndIgnoresTheHandler() {
        DeclaredContract declared = DeclaredContract.parse("rest", "GET /api/admin/tier-spreads");
        assertThat(declared.members()).containsExactly("GET /api/admin/tier-spreads");
        assertThat(declared.missingFrom(
                "GET /api/admin/tier-spreads -> com.trading.admin.TierSpreadsController#tierSpreads\n"))
                .isEmpty();
        assertThat(declared.missingFrom("POST /api/admin/tier-spreads -> com.x.C#m\n"))
                .containsExactly("GET /api/admin/tier-spreads");
    }

    @Test
    void kafkaComparesRoleAndTopic() {
        DeclaredContract declared = DeclaredContract.parse("kafka", "produces orders.v1");
        assertThat(declared.missingFrom("consumes orders.v1\n")).containsExactly("produces orders.v1");
        assertThat(declared.missingFrom("produces orders.v1\n")).isEmpty();
    }

    @Test
    void aKafkaDeclarationMatchesTheExtractorsOwnProducerConsumerVocabulary() {
        // KafkaExtractor only ever writes the literals PRODUCER/CONSUMER into KafkaUse.role(), and
        // ContractActualizer.kafka() emits that field verbatim. A declared "consumes <topic>" that
        // did not canonicalize onto it would report DIVERGED_FROM_PLAN for every correct kafka
        // implementation there is.
        DeclaredContract declared = DeclaredContract.parse("kafka", "consumes t.orders");
        assertThat(declared.problems()).isEmpty();
        assertThat(declared.missingFrom("# actualized (kafka)\nCONSUMER t.orders\n")).isEmpty();
        assertThat(DeclaredContract.parse("kafka", "produces t.orders")
                .missingFrom("# actualized (kafka)\nPRODUCER t.orders\n")).isEmpty();
        // and the roles still do not cross: a declared producer is not met by a consumer
        assertThat(DeclaredContract.parse("kafka", "produces t.orders")
                .missingFrom("# actualized (kafka)\nCONSUMER t.orders\n"))
                .containsExactly("produces t.orders");
    }

    @Test
    void aKafkaDeclarationMayAlsoBeWrittenInTheExtractorsSpelling() {
        // Both spellings are accepted and canonicalize to one, so a human who copied a role out of
        // an actualized body is not punished for it.
        DeclaredContract declared = DeclaredContract.parse("kafka", "PRODUCER t.orders");
        assertThat(declared.problems()).isEmpty();
        assertThat(declared.members()).containsExactly("produces t.orders");
        assertThat(declared.missingFrom("PRODUCER t.orders\n")).isEmpty();
    }

    @Test
    void anUnknownKafkaRoleIsAProblemNotAnUnmatchableMember() {
        DeclaredContract declared = DeclaredContract.parse("kafka", "reads t.orders");
        assertThat(declared.members()).isEmpty();
        assertThat(declared.problems()).singleElement().asString()
                .contains("reads t.orders").contains("produces").contains("consumes");
    }

    @Test
    void blankDeclaredTextIsEmptyAndNotAProblem() {
        DeclaredContract declared = DeclaredContract.parse("java-api", "   \n\n  ");
        assertThat(declared.isEmpty()).isTrue();
        assertThat(declared.problems()).isEmpty();
    }

    @Test
    void aMalformedJavaApiLineIsAProblemNotASilentSkip() {
        DeclaredContract declared = DeclaredContract.parse("java-api", "resolveTier(String): ClientTier");
        assertThat(declared.problems()).hasSize(1);
        assertThat(declared.problems().get(0)).contains("resolveTier(String): ClientTier")
                .contains("<fqcn>#<signature>: <returnType>");
        assertThat(declared.members()).isEmpty();
    }

    @Test
    void anUnqualifiedFqcnIsAGrammarProblemNotASelectorThatMatchesNothing() {
        // ContractActualizer selects types by EXACT fqcn equality, so an unqualified name selects
        // nothing: the body comes back empty and Gate 2 reports the grossest divergence there is
        // for what was only a notation slip. Note the asymmetry that makes this easy to trip over —
        // parameter and return TYPES deliberately compare by simple name, so the fqcn is the one
        // place a human must be exactly right, and nothing else tells them.
        DeclaredContract declared = DeclaredContract.parse("java-api",
                "TierResolver#tierFor(String): Tier");
        assertThat(declared.members()).isEmpty();
        assertThat(declared.problems()).singleElement().asString()
                .contains("TierResolver#tierFor(String): Tier").contains("fully qualified");
    }

    @Test
    void commentAndBlankLinesAreIgnored() {
        DeclaredContract declared = DeclaredContract.parse("rest", """
                # the admin surface
                GET /api/admin/tier-spreads

                """);
        assertThat(declared.members()).containsExactly("GET /api/admin/tier-spreads");
        assertThat(declared.problems()).isEmpty();
    }

    @Test
    void anUnknownKindIsNeverDeclarable() {
        assertThat(ContractKinds.declarable("grpc")).isFalse();
        assertThat(DeclaredContract.parse("grpc", "whatever").problems())
                .singleElement().asString().contains("grpc");
    }

    @Test
    void aDeclaredVarargsParameterMatchesTheBareComponentTypeTheExtractorEmits() {
        DeclaredContract declared = DeclaredContract.parse("java-api",
                "com.trading.audit.Log#write(String, Object...): void");
        assertThat(declared.members()).containsExactly("com.trading.audit.Log#write(String,Object):void");
        assertThat(declared.missingFrom("""
                com.trading.audit.Log
                  write(String, Object): void
                """)).isEmpty();
    }

    @Test
    void aDeclaredArrayParameterMatchesTheBareComponentTypeTheExtractorEmits() {
        DeclaredContract declared = DeclaredContract.parse("java-api",
                "com.trading.audit.Log#write(java.lang.String[]): void");
        assertThat(declared.missingFrom("""
                com.trading.audit.Log
                  write(String[]): void
                """)).isEmpty();
    }

    @Test
    void aDeclaredWildcardInAGenericReturnTypeMatchesTheActualForm() {
        DeclaredContract declared = DeclaredContract.parse("java-api",
                "com.trading.audit.Log#items(): java.util.List<? extends java.lang.String>");
        assertThat(declared.missingFrom("""
                com.trading.audit.Log
                  items(): List<? extends String>
                """)).isEmpty();
    }
}
