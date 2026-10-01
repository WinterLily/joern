final trace = <String>[];

abstract class Slot {
  String get value;
  set value(String input);
}

class Plain implements Slot {
  @override
  String value;
  Plain(this.value);
}

class Fixed implements Slot {
  @override
  final String value;
  Fixed(this.value);
  @override
  set value(String input) {
    trace.add(input);
  }
}

class Derived extends Plain {
  Derived(String value) : super(value);
  @override
  String get value => 'constant';
  @override
  set value(String input) {
    trace.add(input);
  }

  String inherited() => super.value;
  void replace(String input) {
    super.value = input;
  }
}

String direct(String input) => Plain(input).value;
String constant(String input) => Derived(input).value;
String inherited(String input) => Derived(input).inherited();
String throughInterface(Slot receiver, String input) {
  receiver.value = input;
  return receiver.value;
}

class Counter {
  static int next = 0;
}

int lazyIncrement(int input) {
  Counter.next = input;
  return Counter.next++;
}
