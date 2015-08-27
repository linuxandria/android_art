/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.ahat;

import com.android.tools.perflib.heap.ArrayInstance;
import com.android.tools.perflib.heap.ClassInstance;
import com.android.tools.perflib.heap.ClassObj;
import com.android.tools.perflib.heap.Field;
import com.android.tools.perflib.heap.Heap;
import com.android.tools.perflib.heap.Instance;
import com.android.tools.perflib.heap.RootObj;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

class ObjectHandler extends AhatHandler {
  public ObjectHandler(AhatSnapshot snapshot) {
    super(snapshot);
  }

  @Override
  public void handle(Doc doc, Query query) throws IOException {
    long id = query.getLong("id", 0);
    Instance inst = mSnapshot.findInstance(id);
    if (inst == null) {
      doc.println(DocString.text("No object with id %08xl", id));
      return;
    }

    doc.title("Object %08x", inst.getUniqueId());
    doc.big(Value.render(inst));

    printAllocationSite(doc, inst);
    printDominatorPath(doc, inst);

    doc.section("Object Info");
    ClassObj cls = inst.getClassObj();
    doc.descriptions();
    doc.description(DocString.text("Class"), Value.render(cls));
    doc.description(DocString.text("Size"), DocString.text("%d", inst.getSize()));
    doc.description(
        DocString.text("Retained Size"),
        DocString.text("%d", inst.getTotalRetainedSize()));
    doc.description(DocString.text("Heap"), DocString.text(inst.getHeap().getName()));
    doc.end();

    printBitmap(doc, inst);
    if (inst instanceof ClassInstance) {
      printClassInstanceFields(doc, (ClassInstance)inst);
    } else if (inst instanceof ArrayInstance) {
      printArrayElements(doc, (ArrayInstance)inst);
    } else if (inst instanceof ClassObj) {
      printClassInfo(doc, (ClassObj)inst);
    }
    printReferences(doc, inst);
    printDominatedObjects(doc, query, inst);
  }

  private static void printClassInstanceFields(Doc doc, ClassInstance inst) {
    doc.section("Fields");
    doc.table(new Column("Type"), new Column("Name"), new Column("Value"));
    for (ClassInstance.FieldValue field : inst.getValues()) {
      doc.row(
          DocString.text(field.getField().getType().toString()),
          DocString.text(field.getField().getName()),
          Value.render(field.getValue()));
    }
    doc.end();
  }

  private static void printArrayElements(Doc doc, ArrayInstance array) {
    doc.section("Array Elements");
    doc.table(new Column("Index", Column.Align.RIGHT), new Column("Value"));
    Object[] elements = array.getValues();
    for (int i = 0; i < elements.length; i++) {
      doc.row(DocString.text("%d", i), Value.render(elements[i]));
    }
    doc.end();
  }

  private static void printClassInfo(Doc doc, ClassObj clsobj) {
    doc.section("Class Info");
    doc.descriptions();
    doc.description(DocString.text("Super Class"), Value.render(clsobj.getSuperClassObj()));
    doc.description(DocString.text("Class Loader"), Value.render(clsobj.getClassLoader()));
    doc.end();

    doc.section("Static Fields");
    doc.table(new Column("Type"), new Column("Name"), new Column("Value"));
    for (Map.Entry<Field, Object> field : clsobj.getStaticFieldValues().entrySet()) {
      doc.row(
          DocString.text(field.getKey().getType().toString()),
          DocString.text(field.getKey().getName()),
          Value.render(field.getValue()));
    }
    doc.end();
  }

  private static void printReferences(Doc doc, Instance inst) {
    doc.section("Objects with References to this Object");
    if (inst.getHardReferences().isEmpty()) {
      doc.println(DocString.text("(none)"));
    } else {
      doc.table(new Column("Object"));
      for (Instance ref : inst.getHardReferences()) {
        doc.row(Value.render(ref));
      }
      doc.end();
    }

    if (inst.getSoftReferences() != null) {
      doc.section("Objects with Soft References to this Object");
      doc.table(new Column("Object"));
      for (Instance ref : inst.getSoftReferences()) {
        doc.row(Value.render(inst));
      }
      doc.end();
    }
  }

  private void printAllocationSite(Doc doc, Instance inst) {
    doc.section("Allocation Site");
    Site site = mSnapshot.getSiteForInstance(inst);
    SitePrinter.printSite(doc, mSnapshot, site);
  }

  // Draw the bitmap corresponding to this instance if there is one.
  private static void printBitmap(Doc doc, Instance inst) {
    Instance bitmap = InstanceUtils.getAssociatedBitmapInstance(inst);
    if (bitmap != null) {
      doc.section("Bitmap Image");
      doc.println(DocString.image(
            DocString.uri("bitmap?id=%d", bitmap.getId()), "bitmap image"));
    }
  }

  private void printDominatorPath(Doc doc, Instance inst) {
    doc.section("Dominator Path from Root");
    List<Instance> path = new ArrayList<Instance>();
    for (Instance parent = inst;
        parent != null && !(parent instanceof RootObj);
        parent = parent.getImmediateDominator()) {
      path.add(parent);
    }

    // Add 'null' as a marker for the root.
    path.add(null);
    Collections.reverse(path);

    HeapTable.TableConfig<Instance> table = new HeapTable.TableConfig<Instance>() {
      public String getHeapsDescription() {
        return "Bytes Retained by Heap";
      }

      public long getSize(Instance element, Heap heap) {
        if (element == null) {
          return mSnapshot.getHeapSize(heap);
        }
        int index = mSnapshot.getHeapIndex(heap);
        return element.getRetainedSize(index);
      }

      public List<HeapTable.ValueConfig<Instance>> getValueConfigs() {
        HeapTable.ValueConfig<Instance> value = new HeapTable.ValueConfig<Instance>() {
          public String getDescription() {
            return "Object";
          }

          public DocString render(Instance element) {
            if (element == null) {
              return DocString.link(DocString.uri("roots"), DocString.text("ROOT"));
            } else {
              return DocString.text("→ ").append(Value.render(element));
            }
          }
        };
        return Collections.singletonList(value);
      }
    };
    HeapTable.render(doc, table, mSnapshot, path);
  }

  public void printDominatedObjects(Doc doc, Query query, Instance inst) {
    doc.section("Immediately Dominated Objects");
    List<Instance> instances = mSnapshot.getDominated(inst);
    if (instances != null) {
      DominatedList.render(mSnapshot, doc, instances, query);
    } else {
      doc.println(DocString.text("(none)"));
    }
  }
}

