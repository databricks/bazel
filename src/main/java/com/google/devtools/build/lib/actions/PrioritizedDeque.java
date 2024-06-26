package com.google.devtools.build.lib.actions;

import com.google.common.collect.Iterables;
import java.util.Deque;
import java.util.Map;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.TreeMap;
import java.util.function.Function;

import java.lang.UnsupportedOperationException;

// lower priorities are iterated through first
// the only functions that currently really matter are addLast and iterator
public class PrioritizedDeque<E> implements SimpleDeque<E> {
  private final Function<E,Integer> elementToPriority;
  private final TreeMap<Integer, LinkedList<E>> backing = new TreeMap<>();

  public PrioritizedDeque(Function<E,Integer> f) {
    this.elementToPriority = f;
  }

  private LinkedList<E> getSubListFor(E e) {
    int priority = elementToPriority.apply(e);
    backing.putIfAbsent(priority, new LinkedList<E>());
    return backing.get(priority);
  }

  public void addFirst(E e) {
    getSubListFor(e).addFirst(e);
  }

  public void addLast(E e) {
    getSubListFor(e).addLast(e);
  }

  public void clear() {
    // Since the LinkedList in backing are private, not clearing only issue if
    // active iterator() but then clearing is ill-defined anyway.
    backing.clear(); 
  }

  public int size() {
    int size = 0;
    for(LinkedList<E> ll : backing.values()) {
      size += ll.size();
    }
    return size;
  }

  public boolean isEmpty() {
    for(LinkedList<E> ll : backing.values()) {
      if(!ll.isEmpty()) {
        return false;
      }
    }
    return true;
  }

  public Iterator<E> iterator() {
    return Iterables.concat(backing.values()).iterator();
  }
}
