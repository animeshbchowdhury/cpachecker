/* Generated by CIL v. 1.3.6 */
/* print_CIL_Input is true */

#line 1 "test07.c"
int foo(void) 
{ 

  {
#line 2
  return (10);
}
}
#line 5 "test07.c"
int foo2(int x ) 
{ 

  {
#line 6
  x += 1;
#line 6
  return (x + 10);
}
}
#line 9 "test07.c"
int main(void) 
{ int x ;

  {
  {
#line 12
  x = foo();
#line 14
  x = foo2(100);
  }
#line 16
  return (x);
}
}
