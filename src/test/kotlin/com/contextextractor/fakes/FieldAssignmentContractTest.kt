package com.contextextractor.fakes

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Sous-étape 4e-α — vérifie le contrat des extensions de port :
//   • ClassField.isFinal et ClassField.initializerExpression
//   • CodeIntrospector.listFieldAssignments + ordre source garanti
//   • Cohérence assigns() ↔ listFieldAccesses(write=true)
//
// Ces tests sont contractuels : ils valident l'API exposée par le port,
// indépendamment de la stratégie. Le test PSI équivalent est différé à
// l'intégration manuelle (cf README test-project) — la garantie d'ordre
// source de PSI vient gratuitement via JavaRecursiveElementVisitor.
class FieldAssignmentContractTest {

    // ── ClassField.isFinal et ClassField.initializerExpression ────────────────

    @Test
    fun `field isFinal flag is preserved through the port`() {
        val fake = fixture {
            klass("com.test.A") {
                field("logger", T("org.slf4j.Logger"), isFinal = true)
                field("counter", T("int"))
            }
        }
        val cls = fake.resolveClass("com.test.A")!!
        val byName = fake.listFields(cls).associateBy { it.name }
        assertTrue(byName["logger"]!!.isFinal)
        assertFalse(byName["counter"]!!.isFinal)
    }

    @Test
    fun `field initializerExpression is preserved through the port`() {
        val fake = fixture {
            klass("com.test.A") {
                field(
                    "logger",
                    T("org.slf4j.Logger"),
                    initializerExpression = "LoggerFactory.getLogger(A.class)"
                )
                field("nope", T("int"))
            }
        }
        val cls = fake.resolveClass("com.test.A")!!
        val byName = fake.listFields(cls).associateBy { it.name }
        assertEquals("LoggerFactory.getLogger(A.class)", byName["logger"]!!.initializerExpression)
        assertNull(byName["nope"]!!.initializerExpression)
    }

    // ── listFieldAssignments — capture, ordre, cohérence ──────────────────────

    @Test
    fun `listFieldAssignments returns empty when method has no assignment`() {
        val fake = fixture {
            klass("com.test.A") {
                field("repo", T("com.test.Repo"))
                method("findOne") {
                    reads("com.test.A", "repo")
                    calls("com.test.Repo", "findOne")
                }
            }
        }
        val findOne = fake.listMethodsOf("com.test.A").single()
        assertTrue(fake.listFieldAssignments(findOne).isEmpty())
    }

    @Test
    fun `single assignment is captured with rhs and unconditional defaults`() {
        val fake = fixture {
            klass("com.test.A") {
                field("cache", T("java.util.Map"))
                method("init") {
                    assigns(
                        "com.test.A", "cache",
                        rhsExpression = "new HashMap<>()",
                        rhsType = T("java.util.HashMap")
                    )
                }
            }
        }
        val init = fake.listMethodsOf("com.test.A").single()
        val assignments = fake.listFieldAssignments(init)
        assertEquals(1, assignments.size)
        val a = assignments.single()
        assertEquals("com.test.A", a.ownerType)
        assertEquals("cache", a.fieldName)
        assertEquals("new HashMap<>()", a.rhsExpression)
        assertEquals("java.util.HashMap", a.rhsType?.fqName)
        assertFalse(a.isConditional)
        assertFalse(a.conditionIsNullCheck)
    }

    @Test
    fun `multiple assignments are returned in source order`() {
        val fake = fixture {
            klass("com.test.A") {
                field("a", T("int"))
                field("b", T("int"))
                field("c", T("int"))
                method("setup") {
                    assigns("com.test.A", "a", rhsExpression = "1")
                    assigns("com.test.A", "b", rhsExpression = "2")
                    assigns("com.test.A", "c", rhsExpression = "3")
                }
            }
        }
        val setup = fake.listMethodsOf("com.test.A").single()
        val names = fake.listFieldAssignments(setup).map { it.fieldName }
        assertEquals(listOf("a", "b", "c"), names)
    }

    @Test
    fun `conditional assignment carries isConditional flag`() {
        val fake = fixture {
            klass("com.test.A") {
                field("cache", T("com.test.Cache"))
                method("lazyInit") {
                    assigns(
                        "com.test.A", "cache",
                        rhsExpression = "new Cache()",
                        isConditional = true
                    )
                }
            }
        }
        val lazyInit = fake.listMethodsOf("com.test.A").single()
        val a = fake.listFieldAssignments(lazyInit).single()
        assertTrue(a.isConditional)
        assertFalse(a.conditionIsNullCheck)
    }

    @Test
    fun `null-check conditional assignment carries both flags`() {
        val fake = fixture {
            klass("com.test.A") {
                field("cache", T("com.test.Cache"))
                method("ensure") {
                    // pattern lazy-init `if (this.cache == null) this.cache = new Cache();`
                    assigns(
                        "com.test.A", "cache",
                        rhsExpression = "new Cache()",
                        isConditional = true,
                        conditionIsNullCheck = true
                    )
                }
            }
        }
        val ensure = fake.listMethodsOf("com.test.A").single()
        val a = fake.listFieldAssignments(ensure).single()
        assertTrue(a.isConditional)
        assertTrue(a.conditionIsNullCheck)
    }

    @Test
    fun `assigns also produces a write FieldAccess for read-write order analysis`() {
        // §4.5 stratégie 10 (estAutoInitialisé) : la première lecture vs la
        // première assignation se compare via listFieldAccesses + l'ordre
        // d'apparition. assigns() doit donc peupler les DEUX vues du port.
        val fake = fixture {
            klass("com.test.A") {
                field("x", T("int"))
                method("touch") {
                    reads("com.test.A", "x")
                    assigns("com.test.A", "x", rhsExpression = "5")
                }
            }
        }
        val touch = fake.listMethodsOf("com.test.A").single()
        val accesses = fake.listFieldAccesses(touch)
        assertEquals(2, accesses.size, "1 read + 1 write attendus")
        // Ordre source : d'abord la lecture, puis l'assignation.
        assertEquals(false, accesses[0].write, "premier accès = lecture")
        assertEquals(true, accesses[1].write, "second accès = écriture")
        // Et l'assignation aussi exposée par listFieldAssignments.
        val assignments = fake.listFieldAssignments(touch)
        assertEquals(1, assignments.size)
        assertEquals("x", assignments.single().fieldName)
    }

    // ── Découplage : champ assigné dans une classe parent ────────────────────

    @Test
    fun `assignment captured on hierarchy field is reported with super-class ownerType`() {
        val fake = fixture {
            klass("com.test.Top", isAbstract = true) {
                field("base", T("java.lang.String"))
            }
            klass("com.test.Sub", superFqn = "com.test.Top") {
                method("setup") {
                    // Le port ne calcule rien : c'est le caller qui dit
                    // « le champ "base" appartient à Top ». Cohérent avec PSI :
                    // PsiField.getContainingClass() retourne Top.
                    assigns("com.test.Top", "base", rhsExpression = "\"hello\"")
                }
            }
            superChain("com.test.Sub", "com.test.Top")
        }
        val setup = fake.listMethodsOf("com.test.Sub").single()
        val a = fake.listFieldAssignments(setup).single()
        assertEquals("com.test.Top", a.ownerType)
        assertEquals("base", a.fieldName)
        // Cohérence avec listFields : le champ "base" est bien déclaré dans Top.
        val sub = fake.resolveClass("com.test.Sub")!!
        assertNotNull(fake.listSuperClasses(sub).firstOrNull { it.fqn == "com.test.Top" })
    }
}
