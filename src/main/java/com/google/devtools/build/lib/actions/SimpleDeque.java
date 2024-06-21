package com.google.devtools.build.lib.actions;

import java.lang.Iterable;
import java.util.Iterator;
import java.util.LinkedList;

// java.util.Deque has a lot of abstract methods
// only ask to implement the ones we need
public interface SimpleDeque<E> extends Iterable<E> {
  public void addFirst(E e);
  public void addLast(E e);
  public void clear();
  public int size();
  public boolean isEmpty();
  public Iterator<E> iterator();

  static class SimpleDequeOfLinkedList<E> implements SimpleDeque<E> {
    private final LinkedList<E> backing;
    private SimpleDequeOfLinkedList(LinkedList<E> backing) {
      this.backing = backing;
    }

    public void addFirst(E e) { backing.addFirst(e); }
    public void addLast(E e) { backing.addLast(e); }
    public void clear() { backing.clear(); }
    public int size() { return backing.size(); }
    public boolean isEmpty() { return backing.isEmpty(); }
    public Iterator<E> iterator() { return backing.iterator(); }
  }

  public static <E> SimpleDeque<E> of(LinkedList<E> backing) {
    return new SimpleDequeOfLinkedList(backing);
  }
}
