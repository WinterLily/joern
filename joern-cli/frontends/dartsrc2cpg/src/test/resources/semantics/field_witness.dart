class Writer {
  void count(int value) {
    value.toString();
  }

  void writeList<T>(List<T> items, void Function(T) callback) {
    count(items.length);
    for (var i = 0; i < items.length; i++) {
      callback(items[i]);
    }
  }
}

class Item {
  final int value;
  final int other;
  Item(this.value, this.other);
  void write(Writer writer) {
    writer.count(value);
    writer.count(other);
  }
}

class Base {
  final Item header;
  Base(this.header);
  void write(Writer writer) {
    header.write(writer);
  }
}

class Fields extends Base {
  final List<Item> first;
  final List<Item> second;
  Fields(super.header, this.first, this.second);
  @override
  void write(Writer writer) {
    writer.writeList(first, (v) => v.write(writer));
    writer.writeList(second, (v) => v.write(writer));
    super.write(writer);
  }
}

class Derived extends Fields {
  final List<Item> third;
  Derived(super.header, super.first, super.second, this.third);
  @override
  void write(Writer writer) {
    writer.writeList(third, (v) => v.write(writer));
    super.write(writer);
  }
}
