// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.util.io

import org.assertj.core.api.Assertions
import org.assertj.core.api.Assertions.assertThat
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarInputStream
import kotlin.test.fail

class DirectoryContentSpecTest {
  @Test
  fun `files in directory`() {
    val dir = directoryContent {
      file("a.txt")
      file("b.txt")
    }.generateInTempDir()

    dir.assertMatches(directoryContent {
      file("a.txt")
      file("b.txt")
    })

    dir.assertNotMatches(directoryContent {
      file("a.txt")
    }, FileTextMatcher.ignoreBlankLines())

    dir.assertNotMatches(directoryContent {
      file("a.txt")
      file("b.txt")
      file("c.txt")
    }, FileTextMatcher.ignoreBlankLines())
  }

  @Test
  fun `directory in directory`() {
    val dir = directoryContent {
      dir("a") {
        file("a.txt")
      }
    }.generateInTempDir()

    dir.assertMatches(directoryContent {
      dir("a") {
        file("a.txt")
      }
    })

    dir.assertNotMatches(directoryContent {
      dir("b") {
        file("a.txt")
      }
    }, FileTextMatcher.ignoreBlankLines())

    dir.assertNotMatches(directoryContent {
      dir("a") {
        file("b.txt")
      }
    }, FileTextMatcher.ignoreBlankLines())
  }

  @Test
  fun `file content`() {
    val dir = directoryContent {
      file("a.txt", "text")
    }.generateInTempDir()

    dir.assertMatches(directoryContent {
      file("a.txt", "text")
    })

    dir.assertMatches(directoryContent {
      file("a.txt")
    })

    dir.assertNotMatches(directoryContent {
      file("a.txt", "a")
    }, FileTextMatcher.ignoreBlankLines())
  }

  @Test
  fun `file content with ignore empty lines option`() {
    val dir = directoryContent {
      file("a.txt", "a\n\nb")
    }.generateInTempDir()

    dir.assertMatches(directoryContent {
      file("a.txt", "a\n\nb")
    }, FileTextMatcher.ignoreBlankLines())
    dir.assertMatches(directoryContent {
      file("a.txt", "a\nb")
    }, FileTextMatcher.ignoreBlankLines())
    dir.assertMatches(directoryContent {
      file("a.txt", "a\nb\n")
    }, FileTextMatcher.ignoreBlankLines())

    dir.assertNotMatches(directoryContent {
      file("a.txt", "a\nb\nc")
    }, FileTextMatcher.ignoreBlankLines())
  }

  @Test
  fun `file in zip`() {
    val dir = directoryContent {
      zip("a.zip") {
        file("a.txt", "text")
      }
    }.generateInTempDir()

    dir.assertMatches(directoryContent {
      zip("a.zip") {
        file("a.txt", "text")
      }
    })

    dir.assertNotMatches(directoryContent {
      dir("a.zip") {
        file("a.txt", "text")
      }
    }, FileTextMatcher.ignoreBlankLines())

    dir.assertNotMatches(directoryContent {
      zip("a.zip") {
        file("a.txt", "a")
      }
    }, FileTextMatcher.ignoreBlankLines())
  }

  @Test
  fun `merge directory definitions inside directoryContent`() {
    val dir = directoryContent {
      dir("foo") {
        file("a.txt")
      }
      dir("foo") {
        file("b.txt")
      }
    }.generateInTempDir()

    dir.assertMatches(directoryContent {
      dir("foo") {
        file("a.txt")
        file("b.txt")
      }
    })
  }
  
  @Test
  fun `merge multiple directory contents`() {
    val dir = directoryContent {
      dir("foo") {
        file("a.txt")
      }
      file("c.txt", "1")
      file("d.txt")
    }.mergeWith(directoryContent {
      dir("foo") {
        file("b.txt")
      }
      file("c.txt", "2")
      file("e.txt")
    }).generateInTempDir()

    dir.assertMatches(directoryContent {
      dir("foo") {
        file("a.txt")
        file("b.txt")
      }
      file("c.txt", "2")
      file("d.txt")
      file("e.txt")
    })
  }

  @Test
  fun `file path filter`() {
    val dir = directoryContent {
      dir("foo") {
        file("a.txt")
        file("b.xml")
      }
      file("c.txt")
    }.generateInTempDir()

    dir.assertMatches(directoryContent {
      dir("foo") {
        file("a.txt")
        file("c.xml")
      }
      file("c.txt")
    }, filePathFilter = { it.endsWith(".txt")})

    dir.assertNotMatches(directoryContent {
      dir("foo") {
        file("b.xml")
      }
      file("c.txt")
    }, filePathFilter = { it.endsWith(".txt")})
  }

  @Test
  fun `zip file`() {
    val zip = zipFile {
      file("a.txt", "a")
    }.generateInTempDir()
    assertTrue(zip.isFile())
    Assertions.assertThat(zip.fileName.toString()).endsWith(".zip")
    zip.assertMatches(zipFile {
      file("a.txt", "a")
    })
    zip.assertNotMatches(zipFile {
      file("b.txt", "a")
    }, FileTextMatcher.ignoreBlankLines())
    zip.assertNotMatches(zipFile {
      file("a.txt", "b")
    }, FileTextMatcher.ignoreBlankLines())
    zip.assertNotMatches(directoryContent {
      file("a.txt", "b")
    }, FileTextMatcher.ignoreBlankLines())
  }
  
  @Test
  fun `jar file without manifest`() {
    val jar = jarFile {
      file("a.txt", "a")
    }.generateInTempDir()
    assertTrue(jar.isFile())
    Assertions.assertThat(jar.fileName.toString()).endsWith(".jar")
    jar.assertMatches(jarFile {
      file("a.txt", "a")
    })
    jar.assertNotMatches(jarFile {
      file("b.txt", "a")
    }, FileTextMatcher.ignoreBlankLines())
    jar.assertNotMatches(jarFile {
      file("a.txt", "b")
    }, FileTextMatcher.ignoreBlankLines())
    jar.assertNotMatches(directoryContent {
      file("a.txt", "b")
    }, FileTextMatcher.ignoreBlankLines())
  }
  
  @Test
  fun `jar file with manifest`() {
    val jar = jarFile {
      file("a.txt", "a")
      dir("META-INF") {
        file("MANIFEST.MF", """
          |Manifest-Version: 1.0
          |Implementation-Version: 1.0
        """.trimMargin() + "\n")
      }
    }.generateInTempDir()
    val manifest = JarInputStream(jar.inputStream()).use {
      it.manifest
    }
    assertThat(manifest.mainAttributes.getValue(Attributes.Name.IMPLEMENTATION_VERSION)).isEqualTo("1.0")
  }

  @Test
  fun `ignore xml formatting`() {
    val dir = directoryContent {
      file("a.xml", "<root attr=\"value\"></root>")
      file("b.txt", "foo")
    }.generateInTempDir()

    dir.assertMatches(directoryContent {
      file("a.xml", "  <root   attr = \"value\" >  </root> ")
      file("b.txt", "foo")
    }, FileTextMatcher.ignoreXmlFormatting())
    dir.assertNotMatches(directoryContent {
      file("a.xml", "<root attr=\"value2\"></root>")
      file("b.txt", "foo")
    }, FileTextMatcher.ignoreXmlFormatting())
    dir.assertNotMatches(directoryContent {
      file("a.xml", "<root attr=\"value\"></root>")
      file("b.txt", " foo ")
    }, FileTextMatcher.ignoreXmlFormatting())
  }
}

private fun Path.assertNotMatches(spec: DirectoryContentSpec, fileTextMatcher: FileTextMatcher = FileTextMatcher.exact(),
                                  filePathFilter: (String) -> Boolean = { true }) {
  try {
    assertMatches(spec, fileTextMatcher, filePathFilter)
    fail("File matches to spec but it must not")
  }
  catch (ignored: AssertionError) {
  }
}