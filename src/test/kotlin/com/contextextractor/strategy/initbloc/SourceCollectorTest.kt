package com.contextextractor.strategy.initbloc

import com.contextextractor.core.extractor.ClassField
import com.contextextractor.core.extractor.Parameter
import com.contextextractor.core.model.SelectedConstructor
import com.contextextractor.fakes.T
import com.contextextractor.fakes.fixture
import com.contextextractor.core.model.init.InitSource
import com.contextextractor.core.model.init.MethodInitKind
import com.contextextractor.strategies.recursive.initbloc.SourceCollector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Sous-étape 4e-δ — vérifie SourceCollector.collect(field, ctor) sur les 4
// types de InitSource pertinents pour V1 (FieldInitializer, Constructor,
// Setter, MethodInitializer × {ORDINARY, POST_CONSTRUCT}).
class SourceCollectorTest {

    private val noCtor = SelectedConstructor(parameters = emptyList())

    private fun field(name: String, fqType: String, init: String? = null) =
        ClassField(
            name = name,
            type = T(fqType),
            visibility = "private",
            declaredIn = "com.test.A",
            initializerExpression = init
        )

    // 1) Aucune source → liste vide.
    @Test
    fun `no source detected returns empty list`() {
        val fake = fixture {
            klass("com.test.A") {
                field("repo", T("com.test.Repo"))
                method("unrelated")
            }
        }
        val sources = SourceCollector(fake, setOf("com.test.A"))
            .collect(field("repo", "com.test.Repo"), noCtor)
        assertTrue(sources.isEmpty())
    }

    // 2) FieldInitializer — `private final Logger log = LoggerFactory.…;`.
    @Test
    fun `field with initializer expression yields a FieldInitializer source`() {
        val fake = fixture {
            klass("com.test.A") {
                field("log", T("org.slf4j.Logger"),
                    initializerExpression = "LoggerFactory.getLogger(A.class)")
            }
        }
        val sources = SourceCollector(fake, setOf("com.test.A"))
            .collect(field("log", "org.slf4j.Logger", init = "LoggerFactory.getLogger(A.class)"), noCtor)
        val init = sources.filterIsInstance<InitSource.FieldInitializer>().single()
        assertEquals("LoggerFactory.getLogger(A.class)", init.expression)
        assertEquals("org.slf4j.Logger", init.initType.fqName)
    }

    // 3) Constructor strict — match nom + FQN identiques.
    @Test
    fun `constructor with matching name and fqn yields a Constructor source`() {
        val fake = fixture { klass("com.test.A") { field("repo", T("com.test.Repo")) } }
        val ctor = SelectedConstructor(parameters = listOf(
            Parameter(name = "repo", type = T("com.test.Repo"))
        ))
        val sources = SourceCollector(fake, setOf("com.test.A"))
            .collect(field("repo", "com.test.Repo"), ctor)
        val ctorSrc = sources.filterIsInstance<InitSource.Constructor>().single()
        assertEquals("repo", ctorSrc.parameterName)
    }

    // 4) Constructor strict refuse les types différents (verrou FQN).
    @Test
    fun `constructor with same name but different fqn is rejected`() {
        val fake = fixture { klass("com.test.A") { field("repo", T("com.test.Repo")) } }
        // Param `repo` mais de type `com.other.Repo` (FQN différent) → rejet V1.
        val ctor = SelectedConstructor(parameters = listOf(
            Parameter(name = "repo", type = T("com.other.Repo"))
        ))
        val sources = SourceCollector(fake, setOf("com.test.A"))
            .collect(field("repo", "com.test.Repo"), ctor)
        assertTrue(sources.filterIsInstance<InitSource.Constructor>().isEmpty())
    }

    // 5) Setter — public, nom dérivé OK, FQN identique.
    @Test
    fun `public setter with matching derived name and fqn yields a Setter source`() {
        val fake = fixture {
            klass("com.test.A") {
                field("repo", T("com.test.Repo"))
                method("setRepo") { param("v", T("com.test.Repo")) }
            }
        }
        val sources = SourceCollector(fake, setOf("com.test.A"))
            .collect(field("repo", "com.test.Repo"), noCtor)
        val setter = sources.filterIsInstance<InitSource.Setter>().single()
        assertEquals("setRepo", setter.methodName)
        assertEquals("com.test.Repo", setter.parameterType.fqName)
    }

    // 6) Setter rejeté si FQN du param ≠ FQN du champ.
    @Test
    fun `setter with incompatible fqn is rejected`() {
        val fake = fixture {
            klass("com.test.A") {
                field("repo", T("com.test.Repo"))
                method("setRepo") { param("v", T("com.other.Repo")) }
            }
        }
        val sources = SourceCollector(fake, setOf("com.test.A"))
            .collect(field("repo", "com.test.Repo"), noCtor)
        assertTrue(sources.filterIsInstance<InitSource.Setter>().isEmpty())
    }

    // 7) Setter package-private rejeté en V1.
    @Test
    fun `package-private setter is rejected in V1`() {
        val fake = fixture {
            klass("com.test.A") {
                field("repo", T("com.test.Repo"))
                method("setRepo", visibility = "package-private") {
                    param("v", T("com.test.Repo"))
                }
            }
        }
        val sources = SourceCollector(fake, setOf("com.test.A"))
            .collect(field("repo", "com.test.Repo"), noCtor)
        assertTrue(sources.filterIsInstance<InitSource.Setter>().isEmpty(),
            "V1 n'accepte que les setters public")
    }

    // 8) MethodInitializer ORDINARY — méthode privée qui assigne le champ.
    @Test
    fun `private method assigning the field yields ORDINARY MethodInitializer`() {
        val fake = fixture {
            klass("com.test.A") {
                field("cache", T("java.util.Map"))
                method("doInit", visibility = "private") {
                    assigns("com.test.A", "cache", rhsExpression = "new HashMap<>()")
                }
            }
        }
        val sources = SourceCollector(fake, setOf("com.test.A"))
            .collect(field("cache", "java.util.Map"), noCtor)
        val mi = sources.filterIsInstance<InitSource.MethodInitializer>().single()
        assertEquals(MethodInitKind.ORDINARY, mi.kind)
        assertEquals("private", mi.visibility)
        assertFalse(mi.hasNullGuard)
    }

    // 9) MethodInitializer POST_CONSTRUCT — méthode @PostConstruct qui assigne.
    @Test
    fun `PostConstruct annotated method yields POST_CONSTRUCT MethodInitializer`() {
        val fake = fixture {
            klass("com.test.A") {
                field("cache", T("java.util.Map"))
                method("init", annotations = listOf("jakarta.annotation.PostConstruct")) {
                    assigns("com.test.A", "cache", rhsExpression = "new HashMap<>()")
                }
            }
        }
        val sources = SourceCollector(fake, setOf("com.test.A"))
            .collect(field("cache", "java.util.Map"), noCtor)
        val mi = sources.filterIsInstance<InitSource.MethodInitializer>().single()
        assertEquals(MethodInitKind.POST_CONSTRUCT, mi.kind)
    }

    // 10) hasNullGuard — pattern lazy-init `if (this.x == null) this.x = …`.
    @Test
    fun `null-guarded conditional assignment sets hasNullGuard true`() {
        val fake = fixture {
            klass("com.test.A") {
                field("cache", T("java.util.Map"))
                method("ensure") {
                    assigns(
                        "com.test.A", "cache",
                        rhsExpression = "new HashMap<>()",
                        isConditional = true,
                        conditionIsNullCheck = true
                    )
                }
            }
        }
        val sources = SourceCollector(fake, setOf("com.test.A"))
            .collect(field("cache", "java.util.Map"), noCtor)
        val mi = sources.filterIsInstance<InitSource.MethodInitializer>().single()
        assertTrue(mi.hasNullGuard)
    }

    // 11) **Cross-field assignsAlso** — la méthode assigne `target` ET `other` ;
    //     assignsAlso doit contenir `other` mais PAS `target`.
    //     Verrou explicite du piège de la consigne 4e-δ.
    @Test
    fun `assignsAlso contains other fields assigned by the same method`() {
        val fake = fixture {
            klass("com.test.A") {
                field("target", T("int"))
                field("other1", T("int"))
                field("other2", T("int"))
                method("setupAll") {
                    assigns("com.test.A", "target", rhsExpression = "1")
                    assigns("com.test.A", "other1", rhsExpression = "2")
                    assigns("com.test.A", "other2", rhsExpression = "3")
                }
            }
        }
        val sources = SourceCollector(fake, setOf("com.test.A"))
            .collect(field("target", "int"), noCtor)
        val mi = sources.filterIsInstance<InitSource.MethodInitializer>().single()
        // Strict : target exclu, other1 + other2 listés (ordre source préservé).
        assertEquals(listOf("other1", "other2"), mi.assignsAlso)
    }

    // 12) externalCalls — appels vers classe hors hiérarchie filtrés.
    @Test
    fun `externalCalls filter out intra-hierarchy and static calls`() {
        val fake = fixture {
            klass("com.test.A") {
                field("cache", T("java.util.Map"))
                method("init") {
                    assigns("com.test.A", "cache", rhsExpression = "new HashMap<>()")
                    calls("com.test.A", "selfHelper")              // intra → exclu
                    calls("com.test.B", "service")                  // externe instance → inclus
                    calls("com.util.SystemHelper", "now",
                        isStatic = true)                            // static → exclu
                }
                method("selfHelper", visibility = "private")
            }
            klass("com.test.B") { method("service") }
        }
        val sources = SourceCollector(fake, setOf("com.test.A"))
            .collect(field("cache", "java.util.Map"), noCtor)
        val mi = sources.filterIsInstance<InitSource.MethodInitializer>().single()
        assertEquals(1, mi.externalCalls.size, "seul l'appel à com.test.B doit compter")
        assertEquals("com.test.B", mi.externalCalls.single().targetType)
    }

    // 13) Méthode héritée — la super-classe assigne le champ via une méthode
    //     publique. La source doit être collectée (ownerType matchée par hiérarchie).
    @Test
    fun `method inherited from super class is collected when super is in hierarchy`() {
        val fake = fixture {
            klass("com.test.Base", isAbstract = true) {
                field("base", T("java.lang.String"))
                method("setBase") { param("v", T("java.lang.String")) }
            }
            klass("com.test.Sub", superFqn = "com.test.Base")
            superChain("com.test.Sub", "com.test.Base")
        }
        val baseField = ClassField(
            name = "base", type = T("java.lang.String"),
            visibility = "private", declaredIn = "com.test.Base"
        )
        val sources = SourceCollector(fake, setOf("com.test.Sub", "com.test.Base"))
            .collect(baseField, noCtor)
        val setter = sources.filterIsInstance<InitSource.Setter>().single()
        assertEquals("setBase", setter.methodName)
    }

    // 14) Le ctor sélectionné est exclu de la branche MethodInitializer.
    //     (En V1, on exclut TOUS les ctors par souci de simplicité.)
    @Test
    fun `constructors are not reported as MethodInitializer`() {
        val fake = fixture {
            klass("com.test.A") {
                field("repo", T("com.test.Repo"))
                method("<init>", returns = T("com.test.A")) {
                    param("repo", T("com.test.Repo"))
                    assigns("com.test.A", "repo", rhsExpression = "repo")
                }
            }
        }
        val ctor = SelectedConstructor(parameters = listOf(
            Parameter(name = "repo", type = T("com.test.Repo"))
        ))
        val sources = SourceCollector(fake, setOf("com.test.A"))
            .collect(field("repo", "com.test.Repo"), ctor)
        // Constructor source attendue, MethodInitializer interdite.
        assertNotNull(sources.filterIsInstance<InitSource.Constructor>().firstOrNull())
        assertNull(sources.filterIsInstance<InitSource.MethodInitializer>().firstOrNull(),
            "le ctor ne doit pas apparaître comme MethodInitializer (§4.2 + simplification V1)")
    }
}
