/* Generated by CIL v. 1.3.6 */
/* print_CIL_Input is true */

#line 1 "problem02.c"
void foo(int param ) 
{ int val ;

  {
#line 4
  val = param;
#line 6
  if (val == 10) {
#line 7
    param += 1;
  } else {
#line 10
    param -= 1;
  }
#line 12
  return;
}
}
#line 14 "problem02.c"
int main(void) 
{ int s1 ;

  {
  {
#line 17
  s1 = 10;
#line 19
  foo(s1);
  }
#line 21
  return (0);
}
}
