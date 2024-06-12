/*
 * Copyright (C) 2023 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package app.cash.redwood.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.cash.redwood.Modifier
import app.cash.redwood.RedwoodCodegenApi
import app.cash.redwood.layout.testing.RedwoodLayoutTestingWidgetFactory
import app.cash.redwood.lazylayout.testing.RedwoodLazyLayoutTestingWidgetFactory
import app.cash.redwood.protocol.guest.DefaultProtocolState
import app.cash.redwood.protocol.guest.guestRedwoodVersion
import app.cash.redwood.protocol.host.ProtocolBridge
import app.cash.redwood.protocol.host.hostRedwoodVersion
import app.cash.redwood.testing.TestRedwoodComposition
import app.cash.redwood.testing.WidgetValue
import app.cash.redwood.widget.MutableListChildren
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import com.example.redwood.testapp.compose.Button
import com.example.redwood.testapp.compose.ScopedTestRow
import com.example.redwood.testapp.compose.TestRow
import com.example.redwood.testapp.compose.TestScope
import com.example.redwood.testapp.compose.Text
import com.example.redwood.testapp.protocol.guest.TestSchemaProtocolBridge
import com.example.redwood.testapp.protocol.host.TestSchemaProtocolFactory
import com.example.redwood.testapp.testing.TestSchemaTestingWidgetFactory
import com.example.redwood.testapp.widget.TestSchemaWidgetFactory
import com.example.redwood.testapp.widget.TestSchemaWidgetSystem
import kotlin.test.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest

class DirectChangeListenerTest : AbstractChangeListenerTest() {
  override fun <T> CoroutineScope.launchComposition(
    widgetSystem: TestSchemaWidgetSystem<WidgetValue>,
    snapshot: () -> T,
  ) = TestRedwoodComposition(this, widgetSystem, MutableListChildren(), createSnapshot = snapshot)
}

class ProtocolChangeListenerTest : AbstractChangeListenerTest() {
  @OptIn(RedwoodCodegenApi::class)
  override fun <T> CoroutineScope.launchComposition(
    widgetSystem: TestSchemaWidgetSystem<WidgetValue>,
    snapshot: () -> T,
  ): TestRedwoodComposition<T> {
    val state = DefaultProtocolState(
      hostVersion = hostRedwoodVersion,
    )
    val composeBridge = TestSchemaProtocolBridge.create(state)
    val widgetBridge = ProtocolBridge(
      guestVersion = guestRedwoodVersion,
      container = MutableListChildren(),
      factory = TestSchemaProtocolFactory(widgetSystem),
      eventSink = { throw AssertionError() },
    )
    state.initChangesSink(widgetBridge)
    return TestRedwoodComposition(this, composeBridge.widgetSystem, composeBridge.root) {
      state.emitChanges()
      snapshot()
    }
  }
}

@OptIn(RedwoodCodegenApi::class)
abstract class AbstractChangeListenerTest {
  // There is no test parameter injector in multiplatform, so we fake it with subtypes.
  abstract fun <T> CoroutineScope.launchComposition(
    widgetSystem: TestSchemaWidgetSystem<WidgetValue>,
    snapshot: () -> T,
  ): TestRedwoodComposition<T>

  @Test
  fun propertyChangeNotifiesWidget() = runTest {
    val button = ListeningButton()
    val widgetSystem = TestSchemaWidgetSystem(
      TestSchema = object : TestSchemaWidgetFactory<WidgetValue> by TestSchemaTestingWidgetFactory() {
        override fun Button() = button
      },
      RedwoodLayout = RedwoodLayoutTestingWidgetFactory(),
      RedwoodLazyLayout = RedwoodLazyLayoutTestingWidgetFactory(),
    )
    val c = backgroundScope.launchComposition(widgetSystem, button::changes)

    var text by mutableStateOf("hi")
    c.setContent {
      Button(text, onClick = null)
    }
    assertThat(c.awaitSnapshot()).containsExactly("text hi", "onClick false", "modifier Modifier", "onEndChanges")

    text = "hello"
    assertThat(c.awaitSnapshot()).containsExactly("text hello", "onEndChanges")
  }

  @Test
  fun unrelatedPropertyChangeDoesNotNotifyWidget() = runTest {
    val button = ListeningButton()
    val widgetSystem = TestSchemaWidgetSystem(
      TestSchema = object : TestSchemaWidgetFactory<WidgetValue> by TestSchemaTestingWidgetFactory() {
        override fun Button() = button
      },
      RedwoodLayout = RedwoodLayoutTestingWidgetFactory(),
      RedwoodLazyLayout = RedwoodLazyLayoutTestingWidgetFactory(),
    )
    val c = backgroundScope.launchComposition(widgetSystem, button::changes)

    var text by mutableStateOf("hi")
    c.setContent {
      Button("hi", onClick = null)
      Text(text)
    }
    assertThat(c.awaitSnapshot()).containsExactly("text hi", "onClick false", "modifier Modifier", "onEndChanges")

    text = "hello"
    assertThat(c.awaitSnapshot()).isEmpty()
  }

  @Test
  fun modifierChangeNotifiesWidget() = runTest {
    val button = ListeningButton()
    val widgetSystem = TestSchemaWidgetSystem(
      TestSchema = object : TestSchemaWidgetFactory<WidgetValue> by TestSchemaTestingWidgetFactory() {
        override fun Button() = button
      },
      RedwoodLayout = RedwoodLayoutTestingWidgetFactory(),
      RedwoodLazyLayout = RedwoodLazyLayoutTestingWidgetFactory(),
    )
    val c = backgroundScope.launchComposition(widgetSystem, button::changes)

    var modifier by mutableStateOf<Modifier>(Modifier)
    c.setContent {
      Button("hi", onClick = null, modifier = modifier)
    }
    assertThat(c.awaitSnapshot()).containsExactly("text hi", "onClick false", "modifier Modifier", "onEndChanges")

    modifier = with(object : TestScope {}) {
      Modifier.accessibilityDescription("hey")
    }
    assertThat(c.awaitSnapshot()).containsExactly("modifier AccessibilityDescription(value=hey)", "onEndChanges")
  }

  @Test
  fun multipleChangesNotifyWidgetOnce() = runTest {
    val button = ListeningButton()
    val widgetSystem = TestSchemaWidgetSystem(
      TestSchema = object : TestSchemaWidgetFactory<WidgetValue> by TestSchemaTestingWidgetFactory() {
        override fun Button() = button
      },
      RedwoodLayout = RedwoodLayoutTestingWidgetFactory(),
      RedwoodLazyLayout = RedwoodLazyLayoutTestingWidgetFactory(),
    )
    val c = backgroundScope.launchComposition(widgetSystem, button::changes)

    var text by mutableStateOf("hi")
    var modifier by mutableStateOf<Modifier>(Modifier)
    c.setContent {
      Button(text, onClick = null, modifier = modifier)
    }
    assertThat(c.awaitSnapshot()).containsExactly("text hi", "onClick false", "modifier Modifier", "onEndChanges")

    text = "hello"
    modifier = with(object : TestScope {}) {
      Modifier.accessibilityDescription("hey")
    }
    assertThat(c.awaitSnapshot()).containsExactly("text hello", "modifier AccessibilityDescription(value=hey)", "onEndChanges")
  }

  @Test
  fun childrenChangeNotifiesWidget() = runTest {
    val row = ListeningTestRow()
    val widgetSystem = TestSchemaWidgetSystem(
      TestSchema = object : TestSchemaWidgetFactory<WidgetValue> by TestSchemaTestingWidgetFactory() {
        override fun TestRow() = row
      },
      RedwoodLayout = RedwoodLayoutTestingWidgetFactory(),
      RedwoodLazyLayout = RedwoodLazyLayoutTestingWidgetFactory(),
    )
    val c = backgroundScope.launchComposition(widgetSystem, row::changes)

    var two by mutableStateOf(false)
    c.setContent {
      TestRow {
        Button("one", onClick = null)
        if (two) {
          Button("two", onClick = null)
        }
        Button("three", onClick = null)
      }
    }
    assertThat(c.awaitSnapshot()).containsExactly("modifier Modifier", "children insert", "children insert", "onEndChanges")

    two = true
    assertThat(c.awaitSnapshot()).containsExactly("children insert", "onEndChanges")
  }

  @Test
  fun childrenDescendantChangeDoesNotNotifyWidget() = runTest {
    val row = ListeningTestRow()
    val widgetSystem = TestSchemaWidgetSystem(
      TestSchema = object : TestSchemaWidgetFactory<WidgetValue> by TestSchemaTestingWidgetFactory() {
        override fun TestRow() = row
      },
      RedwoodLayout = RedwoodLayoutTestingWidgetFactory(),
      RedwoodLazyLayout = RedwoodLazyLayoutTestingWidgetFactory(),
    )
    val c = backgroundScope.launchComposition(widgetSystem, row::changes)

    var two by mutableStateOf(false)
    c.setContent {
      TestRow {
        ScopedTestRow {
          Button("one", onClick = null)
          if (two) {
            Button("two", onClick = null)
          }
          Button("three", onClick = null)
        }
      }
    }
    assertThat(c.awaitSnapshot()).containsExactly("modifier Modifier", "children insert", "onEndChanges")

    two = true
    assertThat(c.awaitSnapshot()).isEmpty()
  }
}
